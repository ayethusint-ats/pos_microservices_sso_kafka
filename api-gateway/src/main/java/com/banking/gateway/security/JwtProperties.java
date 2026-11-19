package com.banking.gateway.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Same contract as in the services: see user-service's JwtProperties for the explanation. */
@Validated
@ConfigurationProperties(prefix = "banking.security.jwt")
public record JwtProperties(
        @NotBlank String issuerUri,
        @NotBlank String jwkSetUri,
        @NotBlank String audience
) {
}
