package com.banking.paymentservice.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A transfer from one of the caller's accounts to ANY account, identified by its account number
 * (what a customer knows about the recipient). There's no payerId field: the payer is the caller,
 * taken from the token (Phase 8 rule).
 */
public record TransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull @Pattern(regexp = "\\d{10}", message = "must be a 10-digit account number") String targetAccountNumber,
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @Size(max = 140) String description
) {
}
