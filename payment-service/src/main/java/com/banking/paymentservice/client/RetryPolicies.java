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
     * Writes move money: retry ONLY if the request certainly didn't execute (connection refused,
     * refused with a 4xx such as an expired token). After a timeout or 5xx the money may already
     * have moved, so a retry could debit twice. Phase 13's idempotency keys remove that limit.
     */
    static final Predicate<Throwable> WRITE_RETRYABLE = ex ->
            READ_RETRYABLE.test(ex) && !((AccountServiceUnavailableException) ex).isOutcomeUnknown();

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
