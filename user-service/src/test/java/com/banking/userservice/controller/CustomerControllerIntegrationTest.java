package com.banking.userservice.controller;

import com.banking.userservice.repository.CustomerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test: full Spring context, real JPA, H2 database, and the real HTTP layer
 * (validation, JSON, exception handler) through MockMvc.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerRepository customerRepository;

    @BeforeEach
    void cleanDatabase() {
        customerRepository.deleteAll();
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
                .andExpect(header().exists("Location"))
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
