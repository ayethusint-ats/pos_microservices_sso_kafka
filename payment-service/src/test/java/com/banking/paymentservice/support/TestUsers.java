package com.banking.paymentservice.support;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * The demo users from the Keycloak realm, as already-validated JWTs for MockMvc.
 * Each has a distinct raw token value, so tests can check WHICH token was relayed downstream.
 */
public final class TestUsers {

    public static final String ALICE_ID = "7f3c9a2e-1b4d-4e8a-9c6f-2d5b8e1a0f3c";
    public static final String BOB_ID = "b0b5e1c2-3d4f-4a6b-8c9d-0e1f2a3b4c5d";
    public static final String SAM_ID = "5a3e0f1d-2c4b-4d6e-9f8a-1b2c3d4e5f60";
    public static final String ADA_ID = "ada0c0de-1a2b-4c3d-8e4f-5a6b7c8d9e0f";

    public static final String ALICE_TOKEN = "alice-access-token";

    private TestUsers() {
    }

    public static RequestPostProcessor alice() {
        return user(ALICE_ID, ALICE_TOKEN, "CUSTOMER");
    }

    public static RequestPostProcessor bob() {
        return user(BOB_ID, "bob-access-token", "CUSTOMER");
    }

    public static RequestPostProcessor sam() {
        return user(SAM_ID, "sam-access-token", "SUPPORT");
    }

    public static RequestPostProcessor ada() {
        return user(ADA_ID, "ada-access-token", "ADMIN");
    }

    private static RequestPostProcessor user(String subject, String tokenValue, String role) {
        return jwt().jwt(token -> token.subject(subject).tokenValue(tokenValue))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
