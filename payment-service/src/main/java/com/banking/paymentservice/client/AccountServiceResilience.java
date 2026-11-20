package com.banking.paymentservice.client;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The resilience policy for every call from payment-service to account-service.
 *
 * <pre>
 *   Retry( CircuitBreaker( RateLimiter( Bulkhead( HTTP call → domain exception ) ) ) )
 *   └ outermost: each retry attempt goes through the breaker, so an OPEN breaker stops retries too
 * </pre>
 *
 * The HTTP outcome is translated BEFORE the breaker sees it: the breaker counts
 * {@link AccountServiceUnavailableException} as failures and ignores {@link AccountRejectedException}
 * ("insufficient funds" is a healthy service saying no).
 *
 * <p>What may be retried depends on whether repeating it is harmless, see {@link CallType}.
 */
@Component
public class AccountServiceResilience {

    public static final String INSTANCE = "accountService";

    /** How dangerous it is to repeat the call. */
    public enum CallType {
        /** GET: repeating it changes nothing. Retried on any "unavailable". */
        READ,
        /** Moves money: retried only if the request CERTAINLY didn't execute (connection refused, 4xx refusal). */
        WRITE,
        /**
         * The refund of a reversed transfer: like WRITE, but with more attempts and NOT blocked by an
         * open breaker. If the breaker was opened by other customers' failures, giving up here would
         * strand this customer's money, and a refund is rare enough not to overload anyone.
         */
        COMPENSATION
    }

    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final RateLimiter rateLimiter;
    private final Retry readRetry;
    private final Retry writeRetry;
    private final Retry compensationRetry;

    @Autowired
    public AccountServiceResilience(CircuitBreakerRegistry circuitBreakers, RetryRegistry retries,
                                    BulkheadRegistry bulkheads, RateLimiterRegistry rateLimiters) {
        this(circuitBreakers.circuitBreaker(INSTANCE), bulkheads.bulkhead(INSTANCE), rateLimiters.rateLimiter(INSTANCE),
                retries.retry(INSTANCE + "Read"), retries.retry(INSTANCE + "Write"), retries.retry(INSTANCE + "Compensation"));
    }

    public AccountServiceResilience(CircuitBreaker circuitBreaker, Bulkhead bulkhead, RateLimiter rateLimiter,
                                    Retry readRetry, Retry writeRetry, Retry compensationRetry) {
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
        this.rateLimiter = rateLimiter;
        this.readRetry = readRetry;
        this.writeRetry = writeRetry;
        this.compensationRetry = compensationRetry;
    }

    /** No retries, no breaker, no limits: for tests that look at HTTP classification only. */
    public static AccountServiceResilience none() {
        return new AccountServiceResilience(null, null, null, null, null, null);
    }

    public <T> T execute(String operation, CallType type, Supplier<T> call) {
        if (circuitBreaker == null) {
            return call.get();
        }
        // Inside out: each line wraps the previous one.
        Supplier<T> decorated = Bulkhead.decorateSupplier(bulkhead, call);
        decorated = RateLimiter.decorateSupplier(rateLimiter, decorated);
        if (type != CallType.COMPENSATION) {
            decorated = CircuitBreaker.decorateSupplier(circuitBreaker, decorated);
        }
        decorated = Retry.decorateSupplier(switch (type) {
            case READ -> readRetry;
            case WRITE -> writeRetry;
            case COMPENSATION -> compensationRetry;
        }, decorated);
        try {
            return decorated.get();
        } catch (CallNotPermittedException ex) {
            // Fail fast: the request was never sent, so "not executed" is certain.
            throw new AccountServiceUnavailableException(
                    "account-service %s not attempted: circuit breaker is %s".formatted(operation, circuitBreaker.getState()),
                    false, ex);
        } catch (BulkheadFullException ex) {
            throw new AccountServiceUnavailableException(
                    "account-service %s not attempted: too many concurrent calls".formatted(operation), false, ex);
        } catch (RequestNotPermitted ex) {
            throw new AccountServiceUnavailableException(
                    "account-service %s not attempted: outgoing rate limit reached".formatted(operation), false, ex);
        }
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }
}
