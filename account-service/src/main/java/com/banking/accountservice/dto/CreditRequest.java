package com.banking.accountservice.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of the internal credit call. {@code paymentId} ties the ledger change to the payment that
 * caused it: an audit trail now, and the idempotency key in Phase 13.
 */
public record CreditRequest(
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @NotNull UUID paymentId
) {
}
