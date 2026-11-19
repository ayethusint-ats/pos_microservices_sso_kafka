package com.banking.accountservice.controller;

import com.banking.accountservice.TestcontainersConfiguration;
import com.banking.accountservice.repository.AccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.banking.accountservice.support.TestUsers.ALICE_ID;
import static com.banking.accountservice.support.TestUsers.ada;
import static com.banking.accountservice.support.TestUsers.alice;
import static com.banking.accountservice.support.TestUsers.sam;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Back office: SUPPORT reads, ADMIN manages, CUSTOMER is kept out entirely. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminAccountIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void supportAndAdminCanViewAnyCustomersAccounts() throws Exception {
        String id = adminOpensAccountFor(ALICE_ID);

        for (RequestPostProcessor staff : new RequestPostProcessor[]{sam(), ada()}) {
            mockMvc.perform(get("/api/admin/accounts").param("ownerId", ALICE_ID).with(staff))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
            mockMvc.perform(get("/api/admin/accounts/{id}", id).with(staff))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ownerId").value(ALICE_ID));
        }
    }

    @Test
    void customerIsBlockedFromTheBackOfficeByTheUrlRule() throws Exception {
        String id = adminOpensAccountFor(ALICE_ID);

        mockMvc.perform(get("/api/admin/accounts").param("ownerId", ALICE_ID).with(alice()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/accounts/{id}", id).with(alice()))
                .andExpect(status().isForbidden());
    }

    @Test
    void supportIsReadOnly() throws Exception {
        String id = adminOpensAccountFor(ALICE_ID);

        changeStatus(sam(), id, "FROZEN").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
        mockMvc.perform(post("/api/admin/accounts").with(sam()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"%s\",\"type\":\"SAVINGS\",\"currency\":\"THB\"}".formatted(ALICE_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminFreezesAccountAndOwnerCanNoLongerMoveMoney() throws Exception {
        String id = adminOpensAccountFor(ALICE_ID);
        mockMvc.perform(post("/api/accounts/{id}/deposit", id).with(alice())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 100.00}")).andExpect(status().isOk());

        changeStatus(ada(), id, "FROZEN").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FROZEN"));

        mockMvc.perform(post("/api/accounts/{id}/withdraw", id).with(alice())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 10.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        changeStatus(ada(), id, "ACTIVE").andExpect(status().isOk());
        mockMvc.perform(post("/api/accounts/{id}/withdraw", id).with(alice())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 10.00}"))
                .andExpect(status().isOk());
    }

    @Test
    void accountWithMoneyCannotBeClosedAndClosedIsFinal() throws Exception {
        String id = adminOpensAccountFor(ALICE_ID);
        mockMvc.perform(post("/api/accounts/{id}/deposit", id).with(alice())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 5.00}")).andExpect(status().isOk());

        changeStatus(ada(), id, "CLOSED").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BALANCE_NOT_ZERO"));

        mockMvc.perform(post("/api/accounts/{id}/withdraw", id).with(alice())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 5.00}")).andExpect(status().isOk());
        changeStatus(ada(), id, "CLOSED").andExpect(status().isOk());

        changeStatus(ada(), id, "ACTIVE").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    private String adminOpensAccountFor(String ownerId) throws Exception {
        String json = mockMvc.perform(post("/api/admin/accounts").with(ada()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"%s\",\"type\":\"SAVINGS\",\"currency\":\"THB\"}".formatted(ownerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(ownerId))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asText();
    }

    private ResultActions changeStatus(RequestPostProcessor user, String id, String status) throws Exception {
        return mockMvc.perform(patch("/api/admin/accounts/{id}/status", id).with(user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"%s\",\"reason\":\"integration test\"}".formatted(status)));
    }
}
