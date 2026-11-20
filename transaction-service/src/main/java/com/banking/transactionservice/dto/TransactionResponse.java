package com.banking.transactionservice.dto;

import com.banking.transactionservice.entity.AccountTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID accountId,
        String accountNumber,
        String kind,
        String direction,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        UUID paymentId,
        Instant occurredAt
) {
    public static TransactionResponse from(AccountTransaction t) {
        return new TransactionResponse(t.getId(), t.getAccountId(), t.getAccountNumber(), t.getKind(),
                t.getDirection(), t.getAmount(), t.getCurrency(), t.getBalanceAfter(), t.getPaymentId(),
                t.getOccurredAt());
    }
}
