package com.banking.userservice.controller;

import com.banking.userservice.TestcontainersConfiguration;
import com.banking.userservice.repository.CustomerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test: full Spring context, real JPA, real PostgreSQL (Testcontainers), and the real HTTP layer
 * (validation, JSON, exception handler) through MockMvc.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CustomerControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerRepository customerRepository;

    @BeforeEach
    void cleanDatabase() {
        customerRepository.deleteAll();
    }

    /**
     * Every request in this class carries a mock, already-validated JWT (spring-security-test).
     * These tests are about business behavior. Token validation itself is covered in SecurityIntegrationTest.
     */
    @BeforeEach
    void setUpAuthenticatedMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").with(jwt()
                        .jwt(token -> token.subject("7f3c9a2e-1b4d-4e8a-9c6f-2d5b8e1a0f3c"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .build();
    }

    @Test
    void createThenGet() throws Exception {
        String id = createCustomer("kc-100", "jane@example.com");

        mockMvc.perform(get("/api/customers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("jane@example.com"))
                .andExpect(jsonPath("$.address.city").value("Bangkok"))
                .andExpect(jsonPath("$.createdAt").exists());

        mockMvc.perform(get("/api/customers/by-identity/{kc}", "kc-100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void create_returns400WithFieldErrors() throws Exception {
        String invalid = """
                {"keycloakUserId": "", "firstName": "Jane", "lastName": "Doe",
                 "email": "not-an-email", "address": {"street": "x", "city": "y", "postalCode": "1", "country": "Thailand"}}
                """;

        mockMvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.keycloakUserId").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors['address.country']").exists());
    }

    @Test
    void create_returns409ForDuplicateEmail() throws Exception {
        createCustomer("kc-1", "jane@example.com");

        mockMvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("kc-2", "JANE@example.com")))
                .andExpect(status().isConflict());
    }

    @Test
    void update_changesProfile() throws Exception {
        String id = createCustomer("kc-1", "jane@example.com");
        String update = """
                {"firstName": "Janet", "lastName": "Doe", "email": "janet@example.com",
                 "phoneNumber": "+66 81 000 0000",
                 "address": {"street": "2 Sathorn Rd", "city": "Bangkok", "postalCode": "10120", "country": "TH"}}
                """;

        mockMvc.perform(put("/api/customers/{id}", id).contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Janet"))
                .andExpect(jsonPath("$.keycloakUserId").value("kc-1"));
    }

    @Test
    void delete_thenGetReturns404() throws Exception {
        String id = createCustomer("kc-1", "jane@example.com");

        mockMvc.perform(delete("/api/customers/{id}", id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/customers/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }

    @Test
    void list_isPaged() throws Exception {
        createCustomer("kc-1", "a@example.com");
        createCustomer("kc-2", "b@example.com");

        mockMvc.perform(get("/api/customers").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void getById_returns400ForMalformedUuid() throws Exception {
        mockMvc.perform(get("/api/customers/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    private String createCustomer(String keycloakUserId, String email) throws Exception {
        String json = mockMvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(keycloakUserId, email)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/customers/")))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(json);
        return node.get("id").asText();
    }

    private static String createBody(String keycloakUserId, String email) {
        return """
                {"keycloakUserId": "%s", "firstName": "Jane", "lastName": "Doe",
                 "email": "%s", "phoneNumber": "+66 81 234 5678",
                 "address": {"street": "1 Silom Rd", "city": "Bangkok", "postalCode": "10500", "country": "TH"}}
                """.formatted(keycloakUserId, email);
    }
}
