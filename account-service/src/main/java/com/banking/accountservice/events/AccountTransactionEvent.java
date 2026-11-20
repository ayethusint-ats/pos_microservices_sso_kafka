package com.banking.accountservice.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published to {@code banking.account-transactions} after every committed balance change.
 * Key = accountId, so all events of one account land in one partition, in order.
 *
 * <p>This JSON is a PUBLIC CONTRACT between teams: add fields freely (consumers ignore unknown
 * ones), but never rename or remove a field without a new {@code schemaVersion}.
 *
 * @param eventId       unique per event: consumers use it to drop duplicates (at-least-once delivery)
 * @param balanceAfter  the balance right after this change: lets consumers detect gaps
 * @param paymentId     set for TRANSFER_OUT / TRANSFER_IN / REFUND
 */
public record AccountTransactionEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        UUID accountId,
        String accountNumber,
        String ownerId,
        TransactionKind kind,
        String direction,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        UUID paymentId
) {
    public static final String TYPE = "ACCOUNT_TRANSACTION";
    public static final int VERSION = 1;
}
