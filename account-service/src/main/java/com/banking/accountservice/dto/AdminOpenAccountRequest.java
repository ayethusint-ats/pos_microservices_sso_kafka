package com.banking.accountservice.dto;

import com.banking.accountservice.entity.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** An ADMIN opening an account on behalf of a customer (e.g. at a branch). */
public record AdminOpenAccountRequest(
        @NotBlank @Size(max = 36) String ownerId,
        @NotNull AccountType type,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code, e.g. THB") String currency
) {
}
