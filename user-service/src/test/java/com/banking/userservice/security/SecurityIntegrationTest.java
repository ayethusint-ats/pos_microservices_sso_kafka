package com.banking.userservice.security;

import com.banking.userservice.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security filter chain end to end, inside the real Spring context.
 * No Keycloak needed: malformed tokens are rejected before any key lookup, and valid callers
 * are simulated with jwt().
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SecurityIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void requestWithoutTokenGets401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/customers/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("An access token is required"));
    }

    @Test
    void malformedTokenGets401InvalidToken() throws Exception {
        mockMvc.perform(get("/api/customers/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("error=\"invalid_token\"")))
                .andExpect(jsonPath("$.detail").value("The access token is invalid or expired"));
    }

    @Test
    void healthIsPublicButHidesDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void healthShowsDetailsToAdmin() throws Exception {
        mockMvc.perform(get("/actuator/health").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void whoAmIReflectsTheValidatedToken() throws Exception {
        mockMvc.perform(get("/api/whoami").with(jwt()
                        .jwt(token -> token.subject("7f3c9a2e-1b4d-4e8a-9c6f-2d5b8e1a0f3c")
                                .claim("preferred_username", "alice")
                                .claim("azp", "banking-frontend")
                                .audience(java.util.List.of("banking-api")))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("7f3c9a2e-1b4d-4e8a-9c6f-2d5b8e1a0f3c"))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.authorities", hasItem("ROLE_CUSTOMER")));
    }
}
