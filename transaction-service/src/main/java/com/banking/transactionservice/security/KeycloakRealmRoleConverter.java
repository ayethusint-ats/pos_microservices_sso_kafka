package com.banking.transactionservice.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Translates a Keycloak token into Spring Security authorities.
 *
 * <p>Spring only understands the standard {@code scope} claim by default (giving {@code SCOPE_email}, ...).
 * Keycloak puts realm roles in a vendor-specific place:
 * <pre>{@code "realm_access": { "roles": ["CUSTOMER", "offline_access", ...] } }</pre>
 * We turn each role into {@code ROLE_CUSTOMER}, so {@code hasRole("CUSTOMER")} works in Phase 8.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String ROLE_PREFIX = "ROLE_";

    private final JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(scopeConverter.convert(jwt));

        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String name && !name.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + name));
                }
            }
        }
        return authorities;
    }
}
