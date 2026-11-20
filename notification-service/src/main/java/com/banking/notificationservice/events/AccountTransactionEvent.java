package com.banking.notificationservice.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** notification-service's own reading of account-service's contract (banking.account-transactions). */
public record AccountTransactionEvent(
        UUID eventId,
        Instant occurredAt,
        String accountNumber,
        String ownerId,
        String kind,
        BigDecimal amount,
        String currency,
        UUID paymentId
) {
}
