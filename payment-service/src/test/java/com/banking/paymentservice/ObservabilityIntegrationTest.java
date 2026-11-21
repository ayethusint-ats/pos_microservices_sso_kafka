package com.banking.paymentservice;

import com.banking.paymentservice.outbox.OutboxEvent;
import com.banking.paymentservice.outbox.OutboxRepository;
import com.banking.paymentservice.support.StubAccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static com.banking.paymentservice.support.TestUsers.ALICE_TOKEN;
import static com.banking.paymentservice.support.TestUsers.alice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 14 with tracing and metrics export ON: one transfer is ONE trace (the "transfer" span,
 * the four calls to account-service, the outbox event), and its outcome shows up in Prometheus.
 */
@SpringBootTest
@AutoConfigureObservability
@Import(TestcontainersConfiguration.class)
class ObservabilityIntegrationTest {

    private static final StubAccountService accountService = StubAccountService.start();
    private static final UUID SAVINGS = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID BOBS_ACCOUNT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");
    private static final String BOBS_NUMBER = "2000000003";

    @DynamicPropertySource
    static void stub(DynamicPropertyRegistry registry) {
        registry.add("banking.clients.account-service.base-url", accountService::baseUrl);
        registry.add("spring.security.oauth2.client.provider.keycloak.token-uri", accountService::tokenUri);
        registry.add("spring.security.oauth2.client.registration.account-service.client-secret", () -> "test-secret");
        registry.add("banking.payments.recovery.initial-delay-ms", () -> "3600000");
        registry.add("management.otlp.tracing.endpoint", () -> "http://localhost:1/v1/traces");
    }

    @AfterAll
    static void stop() {
        accountService.close();
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accountService.reset();
        accountService.account(SAVINGS, "THB", "ACTIVE", ALICE_TOKEN);
        accountService.recipient(BOBS_ACCOUNT, BOBS_NUMBER, "THB", "ACTIVE");
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void oneTransferIsOneTraceAcrossHttpAndTheOutbox() throws Exception {
        String body = mockMvc.perform(post("/api/payments").with(alice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"%s\",\"targetAccountNumber\":\"%s\",\"amount\":5.00}".formatted(SAVINGS, BOBS_NUMBER)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String paymentId = objectMapper.readTree(body).path("id").asText();

        // Every call to account-service carried a W3C traceparent of the SAME trace.
        var calls = accountService.accountRequests();
        assertThat(calls).hasSize(4).allSatisfy(call -> assertThat(call.traceparent()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01"));
        String traceId = calls.getFirst().traceparent().substring(3, 35);
        assertThat(calls).allSatisfy(call -> assertThat(call.traceparent()).contains(traceId));

        // ...and so does the PAYMENT_COMPLETED event waiting in the outbox.
        OutboxEvent event = outbox.findAll().stream().filter(e -> e.getMessageKey().equals(paymentId)).findFirst().orElseThrow();
        assertThat(event.getTraceParent()).contains(traceId);
    }

    @Test
    void transferOutcomesAndOpenPaymentsAreInPrometheus() throws Exception {
        mockMvc.perform(post("/api/payments").with(alice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"%s\",\"targetAccountNumber\":\"%s\",\"amount\":5.00}".formatted(SAVINGS, BOBS_NUMBER)))
                .andExpect(status().isCreated());

        String metrics = mockMvc.perform(get("/actuator/prometheus"))      // no token: Prometheus has none
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(metrics)
                .contains("banking_transfer_seconds_count{application=\"payment-service\",error=\"none\",idempotency_key=\"absent\",replayed=\"false\",status=\"COMPLETED\"}")
                .contains("banking_transfer_seconds_bucket")                       // histogram → p95 in Prometheus
                .contains("banking_payments_finished_total{application=\"payment-service\",code=\"none\",status=\"COMPLETED\"}")
                .contains("banking_payments_open{application=\"payment-service\",status=\"NEEDS_RECONCILIATION\"}")
                .contains("banking_outbox_oldest_age_seconds")
                .contains("resilience4j_circuitbreaker_state")
                .doesNotContain("payment_id");                                     // IDs never become metric tags
    }
}
