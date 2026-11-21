package com.banking.paymentservice.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Predicate;

/**
 * WHICH failures are worth retrying is a business decision, so it lives in code;
 * HOW OFTEN and how long to wait lives in application.yml (resilience4j.retry.instances.*).
 */
@Configuration(proxyBeanMethods = false)
public class RetryPolicies {

    /** Reads change nothing: retry any "unavailable", except when the breaker already refused the call. */
    static final Predicate<Throwable> READ_RETRYABLE = ex ->
            ex instanceof AccountServiceUnavailableException unavailable
                    && !(unavailable.getCause() instanceof CallNotPermittedException);

    /**
     * Writes move money. Phase 12 retried them ONLY when the request certainly didn't execute,
     * because after a timeout the money might already have moved.
     *
     * <p>Phase 13: account-service executes each payment operation at most once (the payment ID
     * is the idempotency key), so a repeat after a timeout is answered with the original result.
     * Writes are now as safe to retry as reads. Remove the idempotency and this line becomes a bug.
     */
    static final Predicate<Throwable> WRITE_RETRYABLE = READ_RETRYABLE;

    @Bean
    RetryConfigCustomizer readRetryPolicy() {
        return RetryConfigCustomizer.of(AccountServiceResilience.INSTANCE + "Read",
                builder -> builder.retryOnException(READ_RETRYABLE));
    }

    @Bean
    RetryConfigCustomizer writeRetryPolicy() {
        return RetryConfigCustomizer.of(AccountServiceResilience.INSTANCE + "Write",
                builder -> builder.retryOnException(WRITE_RETRYABLE));
    }

    @Bean
    RetryConfigCustomizer compensationRetryPolicy() {
        return RetryConfigCustomizer.of(AccountServiceResilience.INSTANCE + "Compensation",
                builder -> builder.retryOnException(WRITE_RETRYABLE));
    }
}
