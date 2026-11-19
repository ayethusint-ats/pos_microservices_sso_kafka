package com.banking.accountservice.dto;

import com.banking.accountservice.entity.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * A customer opening an account for THEMSELVES. There is deliberately no ownerId field:
 * the owner is the caller ({@code sub} of the token). A client can't open an account
 * in someone else's name just by putting another ID in the JSON.
 */
public record OpenAccountRequest(
        @NotNull AccountType type,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code, e.g. THB") String currency
) {
}
