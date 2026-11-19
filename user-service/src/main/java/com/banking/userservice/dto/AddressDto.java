package com.banking.userservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddressDto(
        @NotBlank @Size(max = 200) String street,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 20) String postalCode,
        @NotBlank
        @Pattern(regexp = "[A-Z]{2}", message = "must be an ISO 3166-1 alpha-2 code, e.g. TH")
        String country
) {
}
