package com.banking.paymentservice.client;

import com.banking.paymentservice.support.StubAccountService;
import com.banking.paymentservice.support.TestAccountClients;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Circuit breaker, retries and bulkhead around the REAL HTTP client and the REAL retry predicates
 * (RetryPolicies), against a stub on a real socket. Wait times are shortened for the test.
 */
class AccountServiceResilienceTest {

    private static final StubAccountService stub = StubAccountService.start();
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String GET = "/api/accounts/" + ACCOUNT;
    private static final String WITHDRAW = "/api/accounts/" + ACCOUNT + "/withdraw";
    private static final String CREDIT = "/internal/accounts/" + ACCOUNT + "/credit";

    private AccountServiceResilience resilience;
    private AccountServiceClient client;

    @BeforeEach
    void setUp() {
        stub.reset();
        stub.account(ACCOUNT, "THB", "ACTIVE");
        stub.recipient(ACCOUNT, "1111111111", "THB", "ACTIVE");
        resilience = resilience(25);
        client = TestAccountClients.build(stub.baseUrl(), stub.tokenUri(), resilience);
        Jwt jwt = Jwt.withTokenValue("alice-access-token").header("alg", "RS256").subject("alice").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void stop() {
        stub.close();
    }

    private long calls(String method, String path) {
        return stub.calls().stream().filter((method + " " + path)::equals).count();
    }

    // ------------------------------------------------------------------ retry: only what is safe (idempotent)

    @Test
    void readIsRetriedOnceAndSucceeds() {
        stub.failNext("GET", GET, 1, 503);

        assertThat(client.getOwnAccount(ACCOUNT).currency()).isEqualTo("THB");
        assertThat(calls("GET", GET)).isEqualTo(2);
    }

    @Test
    void moneyMovingCallIsRetriedAfterAnUnknownOutcomeBecauseItIsIdempotent() {
        stub.failNext("POST", WITHDRAW, 1, 500);   // Phase 12 stopped here: a retry might have debited twice
        UUID paymentId = UUID.randomUUID();

        client.debit(ACCOUNT, BigDecimal.TEN, paymentId);

        assertThat(calls("POST", WITHDRAW)).isEqualTo(2);
        // Both attempts carry the SAME payment ID: account-service executes it at most once.
        assertThat(stub.accountRequests()).filteredOn(r -> r.path().equals(WITHDRAW))
                .allMatch(r -> r.body().contains(paymentId.toString()));
    }

    @Test
    void debitIsUnknownIfAnEarlierAttemptMayStillExecuteEvenWhenTheLastOneWasRefused() {
        stub.problem("POST", WITHDRAW, 422, "INSUFFICIENT_FUNDS", "no money");
        stub.failNext("POST", WITHDRAW, 1, 500);   // 1st attempt: unknown. 2nd: a clean "no".

        // The 1st attempt may still land later, so "rejected" would be a lie. The saga fences it.
        assertThatThrownBy(() -> client.debit(ACCOUNT, BigDecimal.TEN, UUID.randomUUID()))
                .isInstanceOfSatisfying(AccountServiceUnavailableException.class,
                        ex -> assertThat(ex.isOutcomeUnknown()).isTrue());
    }

    @Test
    void moneyMovingCallIsRetriedWhenItCertainlyDidNotExecute() {
        stub.failNext("POST", WITHDRAW, 1, 401);   // refused: nothing happened, safe to try again

        client.debit(ACCOUNT, BigDecimal.TEN, UUID.randomUUID());

        assertThat(calls("POST", WITHDRAW)).isEqualTo(2);
    }

    @Test
    void businessRefusalsAreNeitherRetriedNorCountedAsFailures() {
        stub.problem("POST", WITHDRAW, 422, "INSUFFICIENT_FUNDS", "no money");

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> client.debit(ACCOUNT, BigDecimal.TEN, UUID.randomUUID()))
                    .isInstanceOf(AccountRejectedException.class);
        }
        assertThat(calls("POST", WITHDRAW)).isEqualTo(10);
        assertThat(resilience.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ------------------------------------------------------------------ circuit breaker

    @Test
    void breakerOpensAfterRepeatedFailuresThenFailsFastWithoutCalling() {
        openTheBreaker();
        long before = stub.calls().size();

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.getOwnAccount(ACCOUNT))
                .isInstanceOfSatisfying(AccountServiceUnavailableException.class, ex -> {
                    assertThat(ex.getMessage()).contains("circuit breaker is OPEN");
                    assertThat(ex.isOutcomeUnknown()).isFalse();        // never sent: certain
                });
        assertThat(stub.calls()).hasSize((int) before);                 // account-service wasn't bothered
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(50));
    }

    @Test
    void afterTheWaitAProbeThatSucceedsClosesTheBreaker() throws Exception {
        openTheBreaker();
        stub.account(ACCOUNT, "THB", "ACTIVE");          // account-service recovered
        Thread.sleep(350);                                // > wait-duration-in-open-state (300 ms here)

        client.getOwnAccount(ACCOUNT);                    // HALF_OPEN: probe 1
        assertThat(resilience.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        client.getOwnAccount(ACCOUNT);                    // probe 2 → enough successes

        assertThat(resilience.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void failingProbeReopensTheBreaker() throws Exception {
        openTheBreaker();
        Thread.sleep(350);

        assertThatThrownBy(() -> client.getOwnAccount(ACCOUNT)).isInstanceOf(AccountServiceUnavailableException.class);
        assertThatThrownBy(() -> client.getOwnAccount(ACCOUNT)).isInstanceOf(AccountServiceUnavailableException.class);

        assertThat(resilience.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ------------------------------------------------------------------ compensation

    @Test
    void refundIsNotBlockedByAnOpenBreaker() {
        openTheBreaker();

        client.refund(ACCOUNT, BigDecimal.ONE, UUID.randomUUID());

        assertThat(calls("POST", CREDIT)).isEqualTo(1);   // the customer's money comes back
    }

    @Test
    void refundIsRetriedAfterAnUnknownOutcome() {
        stub.failNext("POST", CREDIT, 1, 500);   // unknown; the refund is idempotent, so try again

        client.refund(ACCOUNT, BigDecimal.ONE, UUID.randomUUID());

        assertThat(calls("POST", CREDIT)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ bulkhead

    @Test
    void bulkheadRejectsCallsBeyondTheConcurrencyLimitImmediately() throws Exception {
        resilience = resilience(1);
        client = TestAccountClients.build(stub.baseUrl(), stub.tokenUri(), resilience);
        stub.slowReply("GET", GET, Duration.ofMillis(250));   // slow but within the 300 ms read timeout

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Object> first = CompletableFuture.supplyAsync(() -> client.getOwnAccount(ACCOUNT), pool);
            Thread.sleep(80);                                     // the first call is now in flight
            assertThatThrownBy(() -> client.getOwnAccount(ACCOUNT))
                    .isInstanceOfSatisfying(AccountServiceUnavailableException.class,
                            ex -> assertThat(ex.getMessage()).contains("too many concurrent calls"));
            assertThat(first.get()).isNotNull();                  // the first one still completes
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ rate limiter

    @Test
    void outgoingRateLimitRejectsTheExcessWithoutCallingAccountService() {
        RateLimiter tight = RateLimiter.of("tight", RateLimiterConfig.custom()
                .limitForPeriod(2).limitRefreshPeriod(Duration.ofMinutes(1)).timeoutDuration(Duration.ZERO).build());
        AccountServiceResilience limited = new AccountServiceResilience(resilience.circuitBreaker(),
                Bulkhead.ofDefaults("b"), tight, Retry.ofDefaults("r1"), Retry.ofDefaults("r2"), Retry.ofDefaults("r3"));
        AccountServiceClient limitedClient = TestAccountClients.build(stub.baseUrl(), stub.tokenUri(), limited);

        limitedClient.getOwnAccount(ACCOUNT);
        limitedClient.getOwnAccount(ACCOUNT);
        assertThatThrownBy(() -> limitedClient.getOwnAccount(ACCOUNT))
                .isInstanceOfSatisfying(AccountServiceUnavailableException.class,
                        ex -> assertThat(ex.getMessage()).contains("rate limit"));
        assertThat(calls("GET", GET)).isEqualTo(2);
        assertThat(resilience.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();   // not the service's fault
    }

    // ------------------------------------------------------------------ helpers

    /** 3 reads × 2 attempts = 6 recorded failures ≥ minimum 5 at 100 % → OPEN. */
    private void openTheBreaker() {
        stub.problem("GET", GET, 503, null, "down");
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.getOwnAccount(ACCOUNT)).isInstanceOf(AccountServiceUnavailableException.class);
        }
        assertThat(resilience.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /** Same shape as application.yml, with test-sized waits. */
    private static AccountServiceResilience resilience(int maxConcurrentCalls) {
        CircuitBreaker breaker = CircuitBreaker.of("accountService", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(300))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(AccountServiceUnavailableException.class)
                .ignoreExceptions(AccountRejectedException.class, BulkheadFullException.class, RequestNotPermitted.class)
                .build());
        Bulkhead bulkhead = Bulkhead.of("accountService", BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentCalls).maxWaitDuration(Duration.ZERO).build());
        RateLimiter rateLimiter = RateLimiter.of("accountService", RateLimiterConfig.custom()
                .limitForPeriod(1000).limitRefreshPeriod(Duration.ofSeconds(1)).timeoutDuration(Duration.ZERO).build());
        Retry read = Retry.of("read", RetryConfig.custom().maxAttempts(2).waitDuration(Duration.ofMillis(10))
                .retryOnException(RetryPolicies.READ_RETRYABLE).build());
        Retry write = Retry.of("write", RetryConfig.custom().maxAttempts(2).waitDuration(Duration.ofMillis(10))
                .retryOnException(RetryPolicies.WRITE_RETRYABLE).build());
        Retry compensation = Retry.of("compensation", RetryConfig.custom().maxAttempts(2).waitDuration(Duration.ofMillis(10))
                .retryOnException(RetryPolicies.WRITE_RETRYABLE).build());
        return new AccountServiceResilience(breaker, bulkhead, rateLimiter, read, write, compensation);
    }

}
