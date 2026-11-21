package com.banking.accountservice.controller;

import com.banking.accountservice.TestcontainersConfiguration;
import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.outbox.OutboxRepository;
import com.banking.accountservice.repository.AccountRepository;
import com.banking.accountservice.repository.PaymentOperationRepository;
import com.banking.accountservice.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.banking.accountservice.support.TestUsers.ADA_ID;
import static com.banking.accountservice.support.TestUsers.ALICE_ID;
import static com.banking.accountservice.support.TestUsers.BOB_ID;
import static com.banking.accountservice.support.TestUsers.alice;
import static com.banking.accountservice.support.TestUsers.paymentService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 13: payment operations are idempotent, and the ledger's rules make retries safe
 * and abuse impossible. Real PostgreSQL: the primary key is part of what's being tested.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentOperationsIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PaymentOperationRepository operations;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private AccountService accountService;

    private MockMvc mockMvc;
    private UUID alices;
    private UUID bobs;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        AccountResponse a = accountService.open(ALICE_ID, AccountType.CHECKING, "THB", ADA_ID);
        accountService.deposit(a.id(), ALICE_ID, new BigDecimal("100.00"));
        alices = a.id();
        bobs = accountService.open(BOB_ID, AccountType.CHECKING, "THB", ADA_ID).id();
    }

    // ------------------------------------------------------------------ debit (customer token)

    @Test
    void theSameDebitTwiceDebitsOnce() throws Exception {
        UUID paymentId = UUID.randomUUID();

        debit(paymentId, "30.00").andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(70.00));
        // e.g. payment-service retrying after a timeout: same answer, no second debit
        debit(paymentId, "30.00").andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(70.00));

        assertThat(balance(alices)).isEqualByComparingTo("70.00");
        assertThat(eventsMentioning(paymentId)).isEqualTo(1);
    }

    @Test
    void reusingAPaymentIdForADifferentDebitIsRefused() throws Exception {
        UUID paymentId = UUID.randomUUID();
        debit(paymentId, "30.00").andExpect(status().isOk());

        debit(paymentId, "31.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(balance(alices)).isEqualByComparingTo("70.00");
    }

    @Test
    void concurrentDuplicatesDebitExactlyOnce() throws Exception {
        UUID paymentId = UUID.randomUUID();
        List<Callable<Integer>> sameRequestFiveTimes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sameRequestFiveTimes.add(() -> debit(paymentId, "10.00").andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            for (Future<Integer> f : pool.invokeAll(sameRequestFiveTimes)) {
                statuses.add(f.get());
            }
        }

        // Winners answer 200 (one executed, the others saw the record); racers that collided get 409
        // and are retried by payment-service. Never a second debit.
        assertThat(statuses).allMatch(s -> s == 200 || s == 409).contains(200);
        assertThat(balance(alices)).isEqualByComparingTo("90.00");
    }

    // ------------------------------------------------------------------ credit / refund (service token)

    @Test
    void creditingTwiceCreditsOnce() throws Exception {
        UUID paymentId = UUID.randomUUID();
        debit(paymentId, "25.00").andExpect(status().isOk());

        credit(bobs, paymentId, "25.00", false).andExpect(status().isOk());
        credit(bobs, paymentId, "25.00", false).andExpect(status().isOk());

        assertThat(balance(bobs)).isEqualByComparingTo("25.00");
        assertThat(balance(alices)).isEqualByComparingTo("75.00");
    }

    @Test
    void noCreditWithoutARecordedDebitSoNoMoneyFromNothing() throws Exception {
        credit(bobs, UUID.randomUUID(), "1000.00", false)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_DEBITED"));

        UUID paymentId = UUID.randomUUID();
        debit(paymentId, "10.00").andExpect(status().isOk());
        credit(bobs, paymentId, "1000.00", false)                   // more than was debited
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(balance(bobs)).isZero();
    }

    @Test
    void aPaymentIsCreditedOrRefundedNeverBoth() throws Exception {
        UUID credited = UUID.randomUUID();
        debit(credited, "10.00").andExpect(status().isOk());
        credit(bobs, credited, "10.00", false).andExpect(status().isOk());
        credit(alices, credited, "10.00", true)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_SETTLED"));

        UUID refunded = UUID.randomUUID();
        debit(refunded, "20.00").andExpect(status().isOk());
        credit(alices, refunded, "20.00", true).andExpect(status().isOk());
        credit(alices, refunded, "20.00", true).andExpect(status().isOk());          // repeat: fine, once
        credit(bobs, refunded, "20.00", false)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_SETTLED"));

        assertThat(balance(alices)).isEqualByComparingTo("90.00");   // 100 - 10 - 20 + 20
        assertThat(balance(bobs)).isEqualByComparingTo("10.00");
    }

    @Test
    void aRefundOnlyGoesBackToTheDebitedAccount() throws Exception {
        UUID paymentId = UUID.randomUUID();
        debit(paymentId, "10.00").andExpect(status().isOk());

        credit(bobs, paymentId, "10.00", true)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    // ------------------------------------------------------------------ resolve (the fence)

    @Test
    void resolvingAnUndebitedPaymentVoidsItAndALateDebitIsRefused() throws Exception {
        UUID paymentId = UUID.randomUUID();

        resolve(paymentId).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("VOIDED"));
        // The debit request that "timed out" finally arrives:
        debit(paymentId, "30.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_VOIDED"));
        resolve(paymentId).andExpect(jsonPath("$.outcome").value("VOIDED"));      // the answer never changes

        assertThat(balance(alices)).isEqualByComparingTo("100.00");
    }

    @Test
    void resolvingADebitedPaymentReportsWhatWasDebited() throws Exception {
        UUID paymentId = UUID.randomUUID();
        debit(paymentId, "30.00").andExpect(status().isOk());

        resolve(paymentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.accountId").value(alices.toString()))
                .andExpect(jsonPath("$.amount").value(30.00));
        assertThat(operations.count()).isPositive();
    }

    @Test
    void onlyPaymentServiceMayResolve() throws Exception {
        mockMvc.perform(post("/internal/accounts/debits/{id}/resolve", UUID.randomUUID()).with(alice()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions debit(UUID paymentId, String amount) throws Exception {
        return mockMvc.perform(post("/api/accounts/{id}/withdraw", alices).with(alice())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":%s,\"paymentId\":\"%s\"}".formatted(amount, paymentId)));
    }

    private ResultActions credit(UUID account, UUID paymentId, String amount, boolean refund) throws Exception {
        return mockMvc.perform(post("/internal/accounts/{id}/credit", account).with(paymentService())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":%s,\"paymentId\":\"%s\",\"refund\":%s}".formatted(amount, paymentId, refund)));
    }

    private ResultActions resolve(UUID paymentId) throws Exception {
        return mockMvc.perform(post("/internal/accounts/debits/{id}/resolve", paymentId).with(paymentService()));
    }

    private BigDecimal balance(UUID account) {
        return accountRepository.findById(account).orElseThrow().getBalance();
    }

    private long eventsMentioning(UUID paymentId) {
        return outbox.findAll().stream().filter(e -> e.getPayload().contains(paymentId.toString())).count();
    }
}
