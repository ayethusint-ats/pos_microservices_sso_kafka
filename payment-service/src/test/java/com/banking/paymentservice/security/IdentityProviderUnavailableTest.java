package com.banking.paymentservice.security;

import com.banking.paymentservice.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "We could not check the token" (503) must be distinguishable from "the token is bad" (401).
 * The JwtDecoder is replaced by a mock that fails the way Nimbus does in each case.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdentityProviderUnavailableTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void unreachableJwksGives503WithRetryAfter() throws Exception {
        when(jwtDecoder.decode(anyString())).thenThrow(new JwtException(
                "I/O error on GET request for \"http://localhost:8180/.../certs\": Connection refused"));

        mockMvc.perform(get("/api/payments").header("Authorization", "Bearer some.jwt.token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "10"))
                .andExpect(jsonPath("$.title").value("Service unavailable"));
    }

    @Test
    void badTokenStillGives401() throws Exception {
        when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("Signed JWT rejected: Invalid signature"));

        mockMvc.perform(get("/api/payments").header("Authorization", "Bearer some.jwt.token"))
                .andExpect(status().isUnauthorized());
    }
}
