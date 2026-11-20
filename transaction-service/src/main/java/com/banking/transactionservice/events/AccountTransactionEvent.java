package com.banking.transactionservice.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * transaction-service's OWN copy of account-service's event contract (topic banking.account-transactions).
 *
 * <p>Not a shared library on purpose: each team owns its reader. Unknown fields are ignored
 * (tolerant reader), so account-service can add fields without breaking us.
 */
public record AccountTransactionEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        UUID accountId,
        String accountNumber,
        String ownerId,
        String kind,
        String direction,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        UUID paymentId
) {
}
