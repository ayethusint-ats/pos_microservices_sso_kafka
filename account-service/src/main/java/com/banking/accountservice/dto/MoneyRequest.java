package com.banking.accountservice.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body for deposit and withdraw. BigDecimal keeps "0.10" exact; a double would not.
 *
 * @param paymentId optional (Phase 11): set by payment-service when the withdrawal is the debit of a
 *                  transfer, so the event says TRANSFER_OUT and links to the payment. Only labels the
 *                  caller's OWN transaction, so a customer setting it gains nothing.
 */
public record MoneyRequest(
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
        UUID paymentId
) {
}
