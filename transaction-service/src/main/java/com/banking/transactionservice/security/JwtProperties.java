package com.banking.transactionservice.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where tokens come from and who they must be meant for. Bound from {@code banking.security.jwt.*}.
 *
 * @param issuerUri  exact value expected in the token's {@code iss} claim
 * @param jwkSetUri  where to download Keycloak's public keys. Kept separate from the issuer
 *                   on purpose: inside Docker the keys are fetched from {@code keycloak:8180},
 *                   while tokens still say {@code iss=http://localhost:8180/...}
 * @param audience   value that must be present in the token's {@code aud} claim
 */
@Validated
@ConfigurationProperties(prefix = "banking.security.jwt")
public record JwtProperties(
        @NotBlank String issuerUri,
        @NotBlank String jwkSetUri,
        @NotBlank String audience
) {
}
