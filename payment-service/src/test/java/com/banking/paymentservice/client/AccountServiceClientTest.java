package com.banking.paymentservice.client;

import com.banking.paymentservice.support.StubAccountService;
import com.banking.paymentservice.support.TestAccountClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static com.banking.paymentservice.support.StubAccountService.SERVICE_TOKEN;
import static com.banking.paymentservice.support.StubAccountService.TOKEN_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP clients exactly as production builds them, against a stub on a real socket
 * (which also plays Keycloak's token endpoint).
 * Focus: which identity each call carries, token caching, and the "did it happen?" classification.
 */
class AccountServiceClientTest {

    private static final StubAccountService stub = StubAccountService.start();
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOBS_ACCOUNT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String BOBS_NUMBER = "2222222222";

    private AccountServiceClient client;

    @BeforeEach
    void setUp() {
        stub.reset();
        client = clientFor(stub.baseUrl());
        signIn("alice-access-token");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    // ------------------------------------------------------------------ two identities

    @Test
    void customerCallsRelayTheCustomersToken() {
        stub.account(ACCOUNT, "THB", "ACTIVE");

        AccountView account = client.getOwnAccount(ACCOUNT);

        assertThat(account.currency()).isEqualTo("THB");
        assertThat(stub.accountRequests().getFirst().authorization()).isEqualTo("Bearer alice-access-token");
        assertThat(stub.tokenRequests()).isZero();   // relay needs no token of our own
    }

    @Test
    void internalCallsUseTheServicesOwnTokenEvenDuringACustomerRequest() {
        stub.recipient(BOBS_ACCOUNT, BOBS_NUMBER, "THB", "ACTIVE");

        AccountView recipient = client.findRecipient(BOBS_NUMBER);

        assertThat(recipient.id()).isEqualTo(BOBS_ACCOUNT);
        assertThat(recipient.ownerId()).isNull();     // the internal API doesn't reveal it
        assertThat(recipient.balance()).isNull();
        StubAccountService.Recorded call = stub.accountRequests().getFirst();
        assertThat(call.authorization()).isEqualTo("Bearer " + SERVICE_TOKEN);
    }

    @Test
    void tokenRequestIsAClientCredentialsGrantAuthenticatedWithTheClientSecret() {
        stub.recipient(BOBS_ACCOUNT, BOBS_NUMBER, "THB", "ACTIVE");

        client.findRecipient(BOBS_NUMBER);

        StubAccountService.Recorded tokenRequest = stub.requests().stream()
                .filter(r -> r.path().equals(TOKEN_PATH)).findFirst().orElseThrow();
        assertThat(tokenRequest.body()).contains("grant_type=client_credentials");
        String basic = Base64.getEncoder().encodeToString("payment-service:test-secret".getBytes(StandardCharsets.UTF_8));
        assertThat(tokenRequest.authorization()).isEqualTo("Basic " + basic);   // secret never in the body/URL
    }

    @Test
    void serviceTokenIsFetchedOnceAndSharedAcrossCustomers() {
        stub.recipient(BOBS_ACCOUNT, BOBS_NUMBER, "THB", "ACTIVE");

        client.findRecipient(BOBS_NUMBER);                                   // during Alice's request
        signIn("bob-access-token");
        client.findRecipient(BOBS_NUMBER);                                   // during Bob's request
        client.credit(BOBS_ACCOUNT, BigDecimal.ONE, UUID.randomUUID());

        assertThat(stub.tokenRequests()).isEqualTo(1);
    }

    @Test
    void creditSendsAmountAndPaymentReference() {
        stub.recipient(BOBS_ACCOUNT, BOBS_NUMBER, "THB", "ACTIVE");
        UUID paymentId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        client.credit(BOBS_ACCOUNT, new BigDecimal("12.50"), paymentId);

        StubAccountService.Recorded call = stub.accountRequests().getFirst();
        assertThat(call.call()).isEqualTo("POST /internal/accounts/" + BOBS_ACCOUNT + "/credit");
        assertThat(call.body()).isEqualTo("{\"amount\":12.50,\"paymentId\":\"" + paymentId + "\",\"refund\":false}");
    }

    @Test
    void refundIsACreditMarkedAsRefund() {
        stub.recipient(BOBS_ACCOUNT, BOBS_NUMBER, "THB", "ACTIVE");

        client.refund(BOBS_ACCOUNT, BigDecimal.ONE, UUID.randomUUID());

        assertThat(stub.accountRequests().getFirst().body()).endsWith("\"refund\":true}");
    }

    @Test
    void keycloakRefusingTheClientMeansTheCallWasNeverMade() {
        stub.recipient(BOBS_ACCOUNT, BOBS_NUMBER, "THB", "ACTIVE");
        stub.tokenEndpointRefuses();

        assertThatThrownBy(() -> client.credit(BOBS_ACCOUNT, BigDecimal.TEN, UUID.randomUUID()))
                .isInstanceOfSatisfying(AccountServiceUnavailableException.class, ex -> {
                    assertThat(ex.isOutcomeUnknown()).isFalse();
                    assertThat(ex.getMessage()).contains("no service token");
                });
        assertThat(stub.accountRequests()).isEmpty();
    }

    @Test
    void rejectedServiceTokenIsDroppedAndANewOneFetched() {
        stub.problem("GET", "/internal/accounts/by-number/" + BOBS_NUMBER, 401, null, "token revoked");
        assertThatThrownBy(() -> client.findRecipient(BOBS_NUMBER))
                .isInstanceOf(AccountServiceUnavailableException.class);

        stub.recipient(BOBS_ACCOUNT, BOBS_NUMBER, "THB", "ACTIVE");
        client.findRecipient(BOBS_NUMBER);

        assertThat(stub.tokenRequests()).isEqualTo(2);   // the 401 evicted the cached token
    }

    // ------------------------------------------------------------------ outcome classification (Phase 9)

    @Test
    void sendsTheAmountAsJson() {
        stub.account(ACCOUNT, "THB", "ACTIVE");

        UUID paymentId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        client.debit(ACCOUNT, new BigDecimal("12.50"), paymentId);

        assertThat(stub.accountRequests().getFirst().call()).isEqualTo("POST /api/accounts/" + ACCOUNT + "/withdraw");
        assertThat(stub.accountRequests().getFirst().body()).isEqualTo("{\"amount\":12.50,\"paymentId\":\"" + paymentId + "\"}");
    }

    @Test
    void businessRefusalKeepsAccountServicesErrorCode() {
        stub.problem("POST", "/api/accounts/" + ACCOUNT + "/withdraw", 422, "INSUFFICIENT_FUNDS", "balance 5.00");

        assertThatThrownBy(() -> client.debit(ACCOUNT, BigDecimal.TEN, UUID.randomUUID()))
                .isInstanceOfSatisfying(AccountRejectedException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_FUNDS");
                    assertThat(ex.getMessage()).isEqualTo("balance 5.00");
                });
    }

    @Test
    void notFoundIsARefusal() {
        assertThatThrownBy(() -> client.getOwnAccount(ACCOUNT))
                .isInstanceOfSatisfying(AccountRejectedException.class, ex -> assertThat(ex.isNotFound()).isTrue());
    }

    @Test
    void serverErrorMeansOutcomeUnknown() {
        stub.problem("POST", "/api/accounts/" + ACCOUNT + "/withdraw", 500, null, "boom");

        assertThatThrownBy(() -> client.debit(ACCOUNT, BigDecimal.TEN, UUID.randomUUID()))
                .isInstanceOfSatisfying(AccountServiceUnavailableException.class,
                        ex -> assertThat(ex.isOutcomeUnknown()).isTrue());
    }

    @Test
    void rejectedTokenMeansNothingHappened() {
        stub.problem("POST", "/api/accounts/" + ACCOUNT + "/withdraw", 401, null, "token expired");

        assertThatThrownBy(() -> client.debit(ACCOUNT, BigDecimal.TEN, UUID.randomUUID()))
                .isInstanceOfSatisfying(AccountServiceUnavailableException.class,
                        ex -> assertThat(ex.isOutcomeUnknown()).isFalse());
    }

    @Test
    void responseTimeoutMeansOutcomeUnknown() {
        stub.account(ACCOUNT, "THB", "ACTIVE");
        stub.slowReply("POST", "/api/accounts/" + ACCOUNT + "/withdraw", Duration.ofSeconds(2));

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.debit(ACCOUNT, BigDecimal.TEN, UUID.randomUUID()))
                .isInstanceOfSatisfying(AccountServiceUnavailableException.class,
                        ex -> assertThat(ex.isOutcomeUnknown()).isTrue());
        // The request DID reach account-service: it was recorded there. We just never got the answer.
        assertThat(stub.calls()).containsExactly("POST /api/accounts/" + ACCOUNT + "/withdraw");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void connectionRefusedMeansNothingHappened() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        AccountServiceClient unreachable = clientFor("http://localhost:" + closedPort);

        assertThatThrownBy(() -> unreachable.debit(ACCOUNT, BigDecimal.TEN, UUID.randomUUID()))
                .isInstanceOfSatisfying(AccountServiceUnavailableException.class,
                        ex -> assertThat(ex.isOutcomeUnknown()).isFalse());
    }

    // ------------------------------------------------------------------ helpers

    private static void signIn(String tokenValue) {
        Jwt jwt = Jwt.withTokenValue(tokenValue).header("alg", "RS256").subject(tokenValue).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    /** Without retries/breaker: these tests are about HTTP classification (resilience: AccountServiceResilienceTest). */
    private static AccountServiceClient clientFor(String accountServiceUrl) {
        return TestAccountClients.build(accountServiceUrl, stub.tokenUri(), AccountServiceResilience.none());
    }
}
