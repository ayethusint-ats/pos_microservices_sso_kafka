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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void cleanDatabase() {
        accountRepository.deleteAll();
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
    void openDepositWithdrawAndCheckBalance() throws Exception {
        String id = openAccount("kc-1", "THB");

        mockMvc.perform(post("/api/accounts/{id}/deposit", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1000.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1000.00));

        mockMvc.perform(post("/api/accounts/{id}/withdraw", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 250.50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(749.50));

        mockMvc.perform(get("/api/accounts/{id}/balance", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(749.50))
                .andExpect(jsonPath("$.currency").value("THB"));
    }

    @Test
    void withdraw_returns422WithCodeWhenFundsInsufficient() throws Exception {
        String id = openAccount("kc-1", "THB");

        mockMvc.perform(post("/api/accounts/{id}/withdraw", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void deposit_returns400ForInvalidAmount() throws Exception {
        String id = openAccount("kc-1", "THB");

        mockMvc.perform(post("/api/accounts/{id}/deposit", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": -5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());

        mockMvc.perform(post("/api/accounts/{id}/deposit", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1.005}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void open_rejectsUnknownCurrency() throws Exception {
        mockMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\": \"kc-1\", \"type\": \"SAVINGS\", \"currency\": \"XYZ\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void open_rejectsUnknownAccountType() throws Exception {
        mockMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\": \"kc-1\", \"type\": \"GOLD\", \"currency\": \"THB\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listByOwnerReturnsOnlyThatOwnersAccounts() throws Exception {
        openAccount("kc-1", "THB");
        openAccount("kc-1", "USD");
        openAccount("kc-2", "THB");

        mockMvc.perform(get("/api/accounts").param("ownerId", "kc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getUnknownAccountReturns404() throws Exception {
        mockMvc.perform(get("/api/accounts/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    private String openAccount(String ownerId, String currency) throws Exception {
        String body = """
                {"ownerId": "%s", "type": "CHECKING", "currency": "%s"}
                """.formatted(ownerId, currency);
        String json = mockMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.accountNumber").isString())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asText();
    }
}
