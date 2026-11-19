package com.banking.paymentservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test of the claim rules (iss, aud, exp). The signature is checked by Nimbus before these run;
 * here we only test OUR policy on the claims.
 */
class TokenValidatorTest {

    private static final String ISSUER = "http://localhost:8180/realms/banking";
    private final OAuth2TokenValidator<Jwt> validator = SecurityConfig.tokenValidator(ISSUER, "banking-api");

    @Test
    void acceptsTokenFromOurRealmForOurApi() {
        assertThat(validate(ISSUER, List.of("banking-api", "account"), Instant.now().plusSeconds(300)).hasErrors())
                .isFalse();
    }

    @Test
    void rejectsTokenFromAnotherIssuer() {
        OAuth2TokenValidatorResult result =
                validate("http://localhost:8180/realms/master", List.of("banking-api"), Instant.now().plusSeconds(300));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anySatisfy(e -> assertThat(e.getDescription()).contains("iss"));
    }

    @Test
    void rejectsTokenMeantForAnotherApi() {
        OAuth2TokenValidatorResult result = validate(ISSUER, List.of("account"), Instant.now().plusSeconds(300));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anySatisfy(e -> assertThat(e.getDescription()).contains("aud"));
    }

    @Test
    void rejectsExpiredTokenBeyondClockSkew() {
        OAuth2TokenValidatorResult result = validate(ISSUER, List.of("banking-api"), Instant.now().minusSeconds(120));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anySatisfy(e -> assertThat(e.getDescription()).contains("expired"));
    }

    @Test
    void toleratesSmallClockSkew() {
        assertThat(validate(ISSUER, List.of("banking-api"), Instant.now().minusSeconds(30)).hasErrors()).isFalse();
    }

    private OAuth2TokenValidatorResult validate(String issuer, List<String> audience, Instant expiresAt) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer(issuer)
                .audience(audience)
                .subject("7f3c9a2e-1b4d-4e8a-9c6f-2d5b8e1a0f3c")
                .issuedAt(expiresAt.minusSeconds(300))
                .expiresAt(expiresAt)
                .build();
        return validator.validate(jwt);
    }
}
