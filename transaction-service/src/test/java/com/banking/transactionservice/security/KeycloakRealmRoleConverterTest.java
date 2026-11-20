package com.banking.transactionservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void mapsRealmRolesToRoleAuthoritiesAndKeepsScopes() {
        Jwt jwt = token(Map.of(
                "scope", "openid email profile",
                "realm_access", Map.of("roles", List.of("CUSTOMER", "offline_access"))));

        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_CUSTOMER", "ROLE_offline_access", "SCOPE_openid", "SCOPE_email", "SCOPE_profile");
    }

    @Test
    void tokenWithoutRealmAccessHasOnlyScopes() {
        Jwt jwt = token(Map.of("scope", "email"));

        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_email");
    }

    @Test
    void ignoresMalformedRolesClaim() {
        Jwt jwt = token(Map.of("realm_access", Map.of("roles", "ADMIN")));   // a string, not a list

        assertThat(converter.convert(jwt)).isEmpty();
    }

    private static Jwt token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("user-1");
        claims.forEach(builder::claim);
        return builder.build();
    }
}
