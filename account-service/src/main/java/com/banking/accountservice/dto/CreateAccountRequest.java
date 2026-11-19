package com.banking.accountservice.dto;

import com.banking.accountservice.entity.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Phase 2: the client sends {@code ownerId}. Phase 8 restricts this: a CUSTOMER's ownerId comes
 * from the JWT, and only an ADMIN may open accounts for someone else.
 */
public record CreateAccountRequest(
        @NotBlank @Size(max = 36) String ownerId,
        @NotNull AccountType type,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code, e.g. THB") String currency
) {
}
