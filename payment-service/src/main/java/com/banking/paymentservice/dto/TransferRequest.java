package com.banking.paymentservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A transfer between two accounts. There's no payerId field: the payer is the caller,
 * taken from the token (Phase 8 rule).
 */
public record TransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID targetAccountId,
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @Size(max = 140) String description
) {
    /** Reported as field "differentAccounts" in the 400 response. */
    @JsonIgnore
    @AssertTrue(message = "source and target account must be different")
    public boolean isDifferentAccounts() {
        return sourceAccountId == null || !sourceAccountId.equals(targetAccountId);
    }
}
