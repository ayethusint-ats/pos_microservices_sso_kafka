package com.banking.accountservice.security;

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
 *
 * <p>Phase 10: CLIENT roles of the Keycloak client that represents THIS service are permissions
 * granted to other applications:
 * <pre>{@code "resource_access": { "account-service": { "roles": ["internal-transfers"] } } }</pre>
 * becomes {@code PERMISSION_internal-transfers}. Client roles of other clients (e.g. Keycloak's
 * own "account" client) are ignored: they mean nothing to this service.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String ROLE_PREFIX = "ROLE_";
    static final String PERMISSION_PREFIX = "PERMISSION_";

    private final JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
    private final String resourceClientId;

    /** @param resourceClientId Keycloak client ID representing this service, e.g. "account-service" */
    public KeycloakRealmRoleConverter(String resourceClientId) {
        this.resourceClientId = resourceClientId;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(scopeConverter.convert(jwt));

        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null) {
            addRoles(realmAccess.get("roles"), ROLE_PREFIX, authorities);
        }

        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        if (resourceAccess != null && resourceAccess.get(resourceClientId) instanceof Map<?, ?> ownClient) {
            addRoles(ownClient.get("roles"), PERMISSION_PREFIX, authorities);
        }
        return authorities;
    }

    private static void addRoles(Object roles, String prefix, Set<GrantedAuthority> authorities) {
        if (roles instanceof Collection<?> names) {
            for (Object role : names) {
                if (role instanceof String name && !name.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority(prefix + name));
                }
            }
        }
    }
}
