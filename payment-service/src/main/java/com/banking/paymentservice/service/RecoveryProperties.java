package com.banking.paymentservice.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Phase 13: when and how hard PaymentRecoveryJob drives unfinished payments.
 *
 * @param gracePeriod    how long the live request "owns" a new payment. Must exceed the request's
 *                       worst case (≈ 17 s, see application.yml), so the job never races it.
 * @param initialBackoff wait after the 1st recovery attempt; doubles each time up to maxBackoff
 * @param maxAttempts    after this many attempts the payment goes to NEEDS_RECONCILIATION (a person)
 */
@ConfigurationProperties("banking.payments.recovery")
public record RecoveryProperties(
        @DefaultValue("30s") Duration gracePeriod,
        @DefaultValue("10s") Duration initialBackoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("10") int maxAttempts,
        @DefaultValue("20") int batchSize
) {
    /** 10 s, 20 s, 40 s, ... capped at maxBackoff. */
    public Duration backoffAfter(int attempts) {
        Duration backoff = initialBackoff.multipliedBy(1L << Math.min(Math.max(attempts - 1, 0), 20));
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }
}
