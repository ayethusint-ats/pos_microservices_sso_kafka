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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.banking.accountservice.support.TestUsers.ALICE_ID;
import static com.banking.accountservice.support.TestUsers.ada;
import static com.banking.accountservice.support.TestUsers.alice;
import static com.banking.accountservice.support.TestUsers.bob;
import static com.banking.accountservice.support.TestUsers.noRole;
import static com.banking.accountservice.support.TestUsers.sam;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Customers and their own accounts: business behavior AND the authorization rules
 * (roles + ownership), against real PostgreSQL.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountSelfServiceIntegrationTest {

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

    // ------------------------------------------------------------- happy path

    @Test
    void customerOpensDepositsWithdrawsAndChecksBalance() throws Exception {
        String id = openAccount(alice(), "THB");

        moneyCall(alice(), id, "deposit", "1000.00").andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1000.00));
        moneyCall(alice(), id, "withdraw", "250.50").andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(749.50));

        mockMvc.perform(get("/api/accounts/{id}/balance", id).with(alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(749.50));
    }

    @Test
    void ownerIsTakenFromTheTokenNotFromTheRequest() throws Exception {
        // A malicious client adds "ownerId" for someone else: the field doesn't exist in the API.
        mockMvc.perform(post("/api/accounts").with(alice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SAVINGS\",\"currency\":\"THB\",\"ownerId\":\"someone-else\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(ALICE_ID));
    }

    @Test
    void customerSeesOnlyOwnAccounts() throws Exception {
        openAccount(alice(), "THB");
        openAccount(alice(), "USD");
        openAccount(bob(), "THB");

        mockMvc.perform(get("/api/accounts").with(alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].ownerId").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is(ALICE_ID))));
    }

    // ------------------------------------------------------------- ownership (IDOR protection)

    @Test
    void bobCannotSeeOrTouchAlicesAccountAndGets404NotForbidden() throws Exception {
        String alicesAccount = openAccount(alice(), "THB");
        moneyCall(alice(), alicesAccount, "deposit", "500.00").andExpect(status().isOk());

        mockMvc.perform(get("/api/accounts/{id}", alicesAccount).with(bob())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/accounts/{id}/balance", alicesAccount).with(bob())).andExpect(status().isNotFound());
        moneyCall(bob(), alicesAccount, "withdraw", "500.00").andExpect(status().isNotFound());
        moneyCall(bob(), alicesAccount, "deposit", "1.00").andExpect(status().isNotFound());

        // Alice's money is untouched.
        mockMvc.perform(get("/api/accounts/{id}/balance", alicesAccount).with(alice()))
                .andExpect(jsonPath("$.balance").value(500.00));
    }

    // ------------------------------------------------------------- roles

    @Test
    void staffCannotUseSelfServiceEndpointsSoTheyCannotMoveMoney() throws Exception {
        String alicesAccount = openAccount(alice(), "THB");

        for (RequestPostProcessor staff : new RequestPostProcessor[]{sam(), ada()}) {
            moneyCall(staff, alicesAccount, "withdraw", "1.00")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("Forbidden"));     // problem+json, not a 500
            moneyCall(staff, alicesAccount, "deposit", "1.00").andExpect(status().isForbidden());
            mockMvc.perform(get("/api/accounts").with(staff)).andExpect(status().isForbidden());
        }
    }

    @Test
    void validTokenWithoutABusinessRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/api/accounts").with(noRole())).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------- business rules still apply

    @Test
    void withdrawingTooMuchGives422WithCode() throws Exception {
        String id = openAccount(alice(), "THB");
        moneyCall(alice(), id, "withdraw", "1.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void invalidInputGives400() throws Exception {
        String id = openAccount(alice(), "THB");
        moneyCall(alice(), id, "deposit", "-5").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
        mockMvc.perform(post("/api/accounts").with(alice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"GOLD\",\"currency\":\"THB\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownCurrencyGives422() throws Exception {
        mockMvc.perform(post("/api/accounts").with(alice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SAVINGS\",\"currency\":\"XYZ\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
    }

    // ------------------------------------------------------------- helpers

    private String openAccount(RequestPostProcessor user, String currency) throws Exception {
        String json = mockMvc.perform(post("/api/accounts").with(user).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CHECKING\",\"currency\":\"%s\"}".formatted(currency)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/accounts/")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions moneyCall(RequestPostProcessor user, String accountId,
                                                                        String action, String amount) throws Exception {
        return mockMvc.perform(post("/api/accounts/{id}/" + action, accountId).with(user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": %s}".formatted(amount)));
    }
}
