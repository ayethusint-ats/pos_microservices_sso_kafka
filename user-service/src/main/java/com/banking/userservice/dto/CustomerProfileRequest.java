package com.banking.userservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The editable profile fields. Used by customers for their own profile (POST/PUT /api/customers/me)
 * and by admins (PUT /api/admin/customers/{id}). The identity link (keycloakUserId) is never part of it.
 */
public record CustomerProfileRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 254) String email,
        @Pattern(regexp = "^\\+?[0-9 ()-]{7,20}$", message = "must be a valid phone number")
        String phoneNumber,
        @NotNull @Valid AddressDto address
) {
}
