package com.banking.accountservice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Lightweight view for the "check balance" use case. */
public record BalanceResponse(
        UUID accountId,
        String accountNumber,
        BigDecimal balance,
        String currency,
        Instant asOf
) {
}
