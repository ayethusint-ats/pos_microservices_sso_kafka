package com.banking.userservice.controller;

import com.banking.userservice.TestcontainersConfiguration;
import com.banking.userservice.repository.CustomerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.banking.userservice.controller.MyProfileIntegrationTest.profileJson;
import static com.banking.userservice.support.TestUsers.ALICE_ID;
import static com.banking.userservice.support.TestUsers.BOB_ID;
import static com.banking.userservice.support.TestUsers.ada;
import static com.banking.userservice.support.TestUsers.alice;
import static com.banking.userservice.support.TestUsers.sam;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/admin/customers: SUPPORT views, ADMIN manages, CUSTOMER is kept out. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminCustomerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerRepository customerRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        customerRepository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void adminOnboardsCustomerAndStaffCanFindIt() throws Exception {
        String id = adminCreates(ALICE_ID, "alice@example.com");

        for (RequestPostProcessor staff : new RequestPostProcessor[]{sam(), ada()}) {
            mockMvc.perform(get("/api/admin/customers").with(staff))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
            mockMvc.perform(get("/api/admin/customers/{id}", id).with(staff))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.keycloakUserId").value(ALICE_ID));
            mockMvc.perform(get("/api/admin/customers/by-identity/{kc}", ALICE_ID).with(staff))
                    .andExpect(jsonPath("$.id").value(id));
        }
    }

    @Test
    void supportCannotChangeAnything() throws Exception {
        String id = adminCreates(ALICE_ID, "alice@example.com");

        mockMvc.perform(put("/api/admin/customers/{id}", id).with(sam()).contentType(MediaType.APPLICATION_JSON)
                .content(profileJson("x@example.com"))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/customers/{id}", id).with(sam())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/customers").with(sam()).contentType(MediaType.APPLICATION_JSON)
                .content(createJson(BOB_ID, "bob@example.com"))).andExpect(status().isForbidden());
    }

    @Test
    void customerCannotBrowseOtherCustomers() throws Exception {
        adminCreates(BOB_ID, "bob@example.com");

        mockMvc.perform(get("/api/admin/customers").with(alice()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void adminUpdatesAndDeletes() throws Exception {
        String id = adminCreates(ALICE_ID, "alice@example.com");

        mockMvc.perform(put("/api/admin/customers/{id}", id).with(ada()).contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("alice.new@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice.new@example.com"));

        mockMvc.perform(delete("/api/admin/customers/{id}", id).with(ada())).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/admin/customers/{id}", id).with(ada())).andExpect(status().isNotFound());
    }

    @Test
    void duplicateEmailIs409() throws Exception {
        adminCreates(ALICE_ID, "alice@example.com");
        mockMvc.perform(post("/api/admin/customers").with(ada()).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(BOB_ID, "ALICE@example.com")))
                .andExpect(status().isConflict());
    }

    @Test
    void listIsPagedAndRejectsUnknownSortField() throws Exception {
        adminCreates(ALICE_ID, "alice@example.com");
        adminCreates(BOB_ID, "bob@example.com");

        mockMvc.perform(get("/api/admin/customers").param("size", "1").with(sam()))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/admin/customers").param("sort", "password").with(sam()))
                .andExpect(status().isBadRequest());
    }

    private String adminCreates(String keycloakUserId, String email) throws Exception {
        String json = mockMvc.perform(post("/api/admin/customers").with(ada()).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(keycloakUserId, email)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/admin/customers/")))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asText();
    }

    private static String createJson(String keycloakUserId, String email) {
        return profileJson(email).replace("{\"firstName\"", "{\"keycloakUserId\": \"" + keycloakUserId + "\", \"firstName\"");
    }
}
