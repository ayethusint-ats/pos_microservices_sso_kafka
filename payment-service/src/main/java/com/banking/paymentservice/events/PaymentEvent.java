package com.banking.paymentservice.events;

import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published to {@code banking.payment-events} when a payment reaches a final status (exactly one
 * per payment: final statuses never change). Unfinished statuses aren't announced.
 * Key = paymentId. Public contract: add fields freely, never rename/remove without a new schemaVersion.
 *
 * @param eventType PAYMENT_COMPLETED, PAYMENT_REJECTED, PAYMENT_FAILED, PAYMENT_REVERSED,
 *                  PAYMENT_NEEDS_RECONCILIATION
 */
public record PaymentEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        UUID paymentId,
        String payerId,
        UUID sourceAccountId,
        String targetAccountNumber,
        BigDecimal amount,
        String currency,
        String description,
        PaymentStatus status,
        String failureCode
) {
    public static final int VERSION = 1;

    public static PaymentEvent of(Payment payment) {
        return new PaymentEvent(UUID.randomUUID(), "PAYMENT_" + payment.getStatus().name(), VERSION, Instant.now(),
                payment.getId(), payment.getPayerId(), payment.getSourceAccountId(), payment.getTargetAccountNumber(),
                payment.getAmount(), payment.getCurrency(), payment.getDescription(), payment.getStatus(),
                payment.getFailureCode());
    }
}
