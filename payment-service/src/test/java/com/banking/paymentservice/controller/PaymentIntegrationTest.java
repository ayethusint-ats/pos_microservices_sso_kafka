package com.banking.paymentservice.controller;

import com.banking.paymentservice.TestcontainersConfiguration;
import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.repository.PaymentRepository;
import com.banking.paymentservice.support.StubAccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.UUID;

import static com.banking.paymentservice.support.TestUsers.ALICE_ID;
import static com.banking.paymentservice.support.TestUsers.ALICE_TOKEN;
import static com.banking.paymentservice.support.TestUsers.ada;
import static com.banking.paymentservice.support.TestUsers.alice;
import static com.banking.paymentservice.support.TestUsers.bob;
import static com.banking.paymentservice.support.TestUsers.sam;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * payment-service end to end inside one JVM: HTTP API + security + PostgreSQL (Testcontainers)
 * + the real REST client talking to a stub account-service over a real socket.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentIntegrationTest {

    private static final StubAccountService accountService = StubAccountService.start();

    private static final UUID SAVINGS = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CHECKING = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID BOBS_ACCOUNT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");

    @DynamicPropertySource
    static void accountServiceStub(DynamicPropertyRegistry registry) {
        registry.add("banking.clients.account-service.base-url", accountService::baseUrl);
        registry.add("banking.clients.account-service.read-timeout", () -> "300ms");
    }

    @AfterAll
    static void stopStub() {
        accountService.close();
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PaymentRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        accountService.reset();
        // From Alice's point of view: two own THB accounts. Bob's account answers 404 (default).
        accountService.account(SAVINGS, "THB", "ACTIVE");
        accountService.account(CHECKING, "THB", "ACTIVE");
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private ResultActions transfer(RequestPostProcessor user, UUID from, UUID to, String amount) throws Exception {
        return mockMvc.perform(post("/api/payments").with(user).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId":"%s","targetAccountId":"%s","amount":%s,"description":"test"}"""
                        .formatted(from, to, amount)));
    }

    private Payment onlyPayment() {
        assertThat(repository.count()).isEqualTo(1);
        return repository.findAll().getFirst();
    }

    // ------------------------------------------------------------------ success + token relay

    @Test
    void transferBetweenOwnAccountsCompletesAndRelaysTheCustomersToken() throws Exception {
        transfer(alice(), SAVINGS, CHECKING, "100.00")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/payments/")))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.payerId").value(ALICE_ID))
                .andExpect(jsonPath("$.currency").value("THB"));

        assertThat(accountService.calls()).containsExactly(
                "GET /api/accounts/" + SAVINGS,
                "GET /api/accounts/" + CHECKING,
                "POST /api/accounts/" + SAVINGS + "/withdraw",
                "POST /api/accounts/" + CHECKING + "/deposit");
        // account-service saw ALICE, not payment-service: it can apply its own ownership rules.
        assertThat(accountService.requests()).allSatisfy(request ->
                assertThat(request.authorization()).isEqualTo("Bearer " + ALICE_TOKEN));
        assertThat(onlyPayment().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    // ------------------------------------------------------------------ refused: nothing moved

    @Test
    void transferToAnotherCustomerIsRejectedWithTokenRelay() throws Exception {
        transfer(alice(), SAVINGS, BOBS_ACCOUNT, "10.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TARGET_ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.paymentStatus").value("REJECTED"))
                .andExpect(jsonPath("$.paymentId").exists());

        assertThat(accountService.calls()).noneMatch(call -> call.startsWith("POST"));
    }

    @Test
    void insufficientFundsComesFromAccountService() throws Exception {
        accountService.problem("POST", "/api/accounts/" + SAVINGS + "/withdraw", 422,
                "INSUFFICIENT_FUNDS", "Account has insufficient funds: balance 5.00, requested 10.00");

        transfer(alice(), SAVINGS, CHECKING, "10.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.detail").value(startsWith("Account has insufficient funds")));

        assertThat(onlyPayment().getStatus()).isEqualTo(PaymentStatus.REJECTED);
    }

    @Test
    void accountServiceErrorBeforeTheDebitIs503WithRetryAfter() throws Exception {
        accountService.problem("GET", "/api/accounts/" + SAVINGS, 503, null, "unavailable");

        transfer(alice(), SAVINGS, CHECKING, "10.00")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"));
    }

    // ------------------------------------------------------------------ failures in the middle

    @Test
    void creditRefusedAfterDebitIsReversed() throws Exception {
        accountService.problem("POST", "/api/accounts/" + CHECKING + "/deposit", 422,
                "ACCOUNT_NOT_ACTIVE", "Account is FROZEN");

        transfer(alice(), SAVINGS, CHECKING, "10.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.paymentStatus").value("REVERSED"))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        assertThat(accountService.calls()).endsWith(
                "POST /api/accounts/" + SAVINGS + "/withdraw",
                "POST /api/accounts/" + CHECKING + "/deposit",
                "POST /api/accounts/" + SAVINGS + "/deposit");   // refund
    }

    @Test
    void debitWithoutAnswerIsAcceptedForReconciliationNotReportedAsFailed() throws Exception {
        accountService.slowReply("POST", "/api/accounts/" + SAVINGS + "/withdraw", Duration.ofSeconds(2));

        transfer(alice(), SAVINGS, CHECKING, "10.00")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("NEEDS_RECONCILIATION"))
                .andExpect(jsonPath("$.failureCode").value("DEBIT_OUTCOME_UNKNOWN"));

        // It shows up in the back-office work queue.
        mockMvc.perform(get("/api/admin/payments").param("status", "NEEDS_RECONCILIATION").with(sam()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ------------------------------------------------------------------ validation

    @Test
    void sameSourceAndTargetIsAValidationError() throws Exception {
        transfer(alice(), SAVINGS, SAVINGS, "10.00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.differentAccounts").exists());
        assertThat(accountService.calls()).isEmpty();
        assertThat(repository.count()).isZero();
    }

    @Test
    void amountMustBePositiveWithTwoDecimals() throws Exception {
        transfer(alice(), SAVINGS, CHECKING, "-5").andExpect(status().isBadRequest());
        transfer(alice(), SAVINGS, CHECKING, "1.005").andExpect(status().isBadRequest());
        assertThat(accountService.calls()).isEmpty();
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void customersSeeOnlyTheirOwnPayments() throws Exception {
        String body = transfer(alice(), SAVINGS, CHECKING, "1.00").andReturn().getResponse().getContentAsString();
        String paymentId = objectMapper.readTree(body).path("id").asText();

        mockMvc.perform(get("/api/payments/{id}", paymentId).with(alice())).andExpect(status().isOk());
        mockMvc.perform(get("/api/payments/{id}", paymentId).with(bob())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/payments").with(bob()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void staffCanReadButNotPay() throws Exception {
        transfer(sam(), SAVINGS, CHECKING, "1.00").andExpect(status().isForbidden());
        transfer(ada(), SAVINGS, CHECKING, "1.00").andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/payments").with(ada())).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/payments").with(alice())).andExpect(status().isForbidden());
        assertThat(accountService.calls()).isEmpty();
    }

    @Test
    void noTokenIs401() throws Exception {
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void paymentListIsNewestFirst() throws Exception {
        transfer(alice(), SAVINGS, CHECKING, "1.00");
        transfer(alice(), SAVINGS, CHECKING, "2.00");

        JsonNode page = objectMapper.readTree(mockMvc.perform(get("/api/payments").with(alice()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.path("content").get(0).path("amount").decimalValue()).isEqualByComparingTo("2.00");
    }
}
