package com.banking.accountservice.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of the internal credit call. {@code paymentId} ties the ledger change to the payment that
 * caused it, and is the idempotency key (Phase 13): the same payment is never credited twice.
 */
public record CreditRequest(
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @NotNull UUID paymentId,
        /** Phase 11: true when a reversed payment returns money to its source (event kind REFUND). */
        boolean refund
) {
}
