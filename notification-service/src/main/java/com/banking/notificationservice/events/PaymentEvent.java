package com.banking.notificationservice.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** notification-service's own reading of payment-service's contract (banking.payment-events). Only the fields it uses. */
public record PaymentEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID paymentId,
        String payerId,
        String targetAccountNumber,
        BigDecimal amount,
        String currency,
        String failureCode
) {
}
