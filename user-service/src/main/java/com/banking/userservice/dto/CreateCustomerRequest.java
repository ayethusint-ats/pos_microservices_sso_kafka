package com.banking.userservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a customer profile.
 *
 * <p>Phase 2 only: the client sends {@code keycloakUserId}. From Phase 8 on, the service
 * takes it from the verified JWT ("sub"), so a caller can't create a profile for someone else.
 */
public record CreateCustomerRequest(
        @NotBlank @Size(max = 36) String keycloakUserId,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 254) String email,
        @Pattern(regexp = "^\\+?[0-9 ()-]{7,20}$", message = "must be a valid phone number")
        String phoneNumber,
        @NotNull @Valid AddressDto address
) {
}
