package com.banking.accountservice.support;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * The demo users from the Keycloak realm, as already-validated JWTs for MockMvc.
 * Same subject IDs as in Keycloak, so tests read like the real system: alice(), bob(), sam(), ada().
 */
public final class TestUsers {

    public static final String ALICE_ID = "7f3c9a2e-1b4d-4e8a-9c6f-2d5b8e1a0f3c";
    public static final String BOB_ID = "b0b5e1c2-3d4f-4a6b-8c9d-0e1f2a3b4c5d";
    public static final String SAM_ID = "5a3e0f1d-2c4b-4d6e-9f8a-1b2c3d4e5f60";
    public static final String ADA_ID = "ada0c0de-1a2b-4c3d-8e4f-5a6b7c8d9e0f";

    private TestUsers() {
    }

    public static RequestPostProcessor alice() {
        return user(ALICE_ID, "CUSTOMER");
    }

    public static RequestPostProcessor bob() {
        return user(BOB_ID, "CUSTOMER");
    }

    public static RequestPostProcessor sam() {
        return user(SAM_ID, "SUPPORT");
    }

    public static RequestPostProcessor ada() {
        return user(ADA_ID, "ADMIN");
    }

    /** A valid token with no business role at all, like payment-service's service account today. */
    public static RequestPostProcessor noRole() {
        return jwt().jwt(token -> token.subject("2b5bcd1a-793b-478a-bb23-ba4f237cb143"));
    }

    private static RequestPostProcessor user(String subject, String role) {
        return jwt().jwt(token -> token.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
