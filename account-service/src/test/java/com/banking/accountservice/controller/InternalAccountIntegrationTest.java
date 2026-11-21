package com.banking.accountservice.controller;

import com.banking.accountservice.TestcontainersConfiguration;
import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.repository.AccountRepository;
import com.banking.accountservice.service.AccountService;
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

import java.math.BigDecimal;
import java.util.UUID;

import static com.banking.accountservice.support.TestUsers.ADA_ID;
import static com.banking.accountservice.support.TestUsers.ALICE_ID;
import static com.banking.accountservice.support.TestUsers.BOB_ID;
import static com.banking.accountservice.support.TestUsers.ada;
import static com.banking.accountservice.support.TestUsers.alice;
import static com.banking.accountservice.support.TestUsers.noRole;
import static com.banking.accountservice.support.TestUsers.paymentService;
import static com.banking.accountservice.support.TestUsers.sam;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The machine-to-machine API: only payment-service's permission opens it, and it reveals
 * and does as little as possible.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InternalAccountIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

    private MockMvc mockMvc;
    private AccountResponse bobsAccount;
    private AccountResponse alicesAccount;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        bobsAccount = accountService.open(BOB_ID, AccountType.CHECKING, "THB", ADA_ID);
        alicesAccount = accountService.open(ALICE_ID, AccountType.CHECKING, "THB", ADA_ID);
        accountService.deposit(alicesAccount.id(), ALICE_ID, new BigDecimal("100000.00"));
    }

    /** Phase 13: a credit is only possible for a payment whose debit is recorded, so debit Alice first. */
    private ResultActions credit(RequestPostProcessor caller, UUID accountId, String amount) throws Exception {
        UUID paymentId = UUID.randomUUID();
        if (new BigDecimal(amount).signum() > 0) {
            accountService.withdraw(alicesAccount.id(), ALICE_ID, new BigDecimal(amount), paymentId);
        }
        return mockMvc.perform(post("/internal/accounts/{id}/credit", accountId).with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":%s,\"paymentId\":\"%s\"}".formatted(amount, paymentId)));
    }

    @Test
    void paymentServiceFindsARecipientByNumberWithoutLearningOwnerOrBalance() throws Exception {
        mockMvc.perform(get("/internal/accounts/by-number/{n}", bobsAccount.accountNumber()).with(paymentService()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bobsAccount.id().toString()))
                .andExpect(jsonPath("$.currency").value("THB"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.balance").doesNotExist());
    }

    @Test
    void paymentServiceCreditsAnotherCustomersAccount() throws Exception {
        credit(paymentService(), bobsAccount.id(), "25.00").andExpect(status().isOk());

        assertThat(accountRepository.findById(bobsAccount.id()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("25.00"));
    }

    @Test
    void creditStillObeysTheAccountRules() throws Exception {
        accountService.changeStatus(bobsAccount.id(), AccountStatus.FROZEN, "test", ADA_ID);

        credit(paymentService(), bobsAccount.id(), "25.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        credit(paymentService(), UUID.randomUUID(), "1.00").andExpect(status().isNotFound());
    }

    @Test
    void invalidInputIs400() throws Exception {
        mockMvc.perform(get("/internal/accounts/by-number/{n}", "12; drop").with(paymentService()))
                .andExpect(status().isBadRequest());
        credit(paymentService(), bobsAccount.id(), "-1").andExpect(status().isBadRequest());
        mockMvc.perform(post("/internal/accounts/{id}/credit", bobsAccount.id()).with(paymentService())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1.00}"))   // no paymentId
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownAccountNumberIs404() throws Exception {
        mockMvc.perform(get("/internal/accounts/by-number/{n}", "0000000000").with(paymentService()))
                .andExpect(status().isNotFound());
    }

    @Test
    void peopleCannotUseTheInternalApiWhateverTheirRole() throws Exception {
        for (RequestPostProcessor person : new RequestPostProcessor[]{alice(), sam(), ada()}) {
            credit(person, bobsAccount.id(), "1000.00").andExpect(status().isForbidden());
            mockMvc.perform(get("/internal/accounts/by-number/{n}", bobsAccount.accountNumber()).with(person))
                    .andExpect(status().isForbidden());
        }
        credit(noRole(), bobsAccount.id(), "1000.00").andExpect(status().isForbidden());
        assertThat(accountRepository.findById(bobsAccount.id()).orElseThrow().getBalance()).isZero();
    }

    @Test
    void theServiceIdentityHasNoCustomerPowers() throws Exception {
        // The permission is for /internal only: it can't list, withdraw or use the back office.
        mockMvc.perform(get("/api/accounts").with(paymentService())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/accounts/{id}/withdraw", bobsAccount.id()).with(paymentService())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1.00}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/accounts").param("ownerId", BOB_ID).with(paymentService()))
                .andExpect(status().isForbidden());
    }

    @Test
    void noTokenIs401() throws Exception {
        mockMvc.perform(get("/internal/accounts/by-number/{n}", bobsAccount.accountNumber()))
                .andExpect(status().isUnauthorized());
    }
}
