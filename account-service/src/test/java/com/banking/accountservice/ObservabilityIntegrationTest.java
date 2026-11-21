package com.banking.accountservice;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.outbox.OutboxEvent;
import com.banking.accountservice.outbox.OutboxRepository;
import com.banking.accountservice.service.AccountService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.banking.accountservice.support.TestUsers.ALICE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 14 with tracing and metrics export ON (Boot switches them off in other tests).
 * The key claim: a trace survives the outbox. The event is sent later, by another thread,
 * yet the Kafka record carries the trace of the transaction that wrote it.
 */
@SpringBootTest(properties = "management.otlp.tracing.endpoint=http://localhost:1/v1/traces")
@AutoConfigureObservability
@Import(TestcontainersConfiguration.class)
class ObservabilityIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private Tracer tracer;

    @Autowired
    private KafkaConnectionDetails kafkaConnection;

    @Autowired
    private WebApplicationContext context;

    @Test
    void theKafkaEventContinuesTheTraceOfTheRequestThatChangedTheBalance() {
        AccountResponse account = accountService.open(ALICE_ID, AccountType.SAVINGS, "THB", ALICE_ID);
        Span request = tracer.nextSpan().name("POST /api/accounts/{id}/deposit").start();   // as if an HTTP request
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            accountService.deposit(account.id(), ALICE_ID, new BigDecimal("10.00"));
        } finally {
            request.end();
        }
        String traceId = request.context().traceId();

        OutboxEvent row = outbox.findAll().stream()
                .filter(e -> e.getMessageKey().equals(account.id().toString())).findFirst().orElseThrow();
        assertThat(row.getTraceParent()).startsWith("00-" + traceId + "-");

        // The relay sends it a moment later from its own thread, in the same trace.
        ConsumerRecord<String, String> record = consume(account.id().toString());
        Header traceparent = record.headers().lastHeader("traceparent");
        assertThat(traceparent).isNotNull();
        assertThat(new String(traceparent.value(), StandardCharsets.UTF_8)).startsWith("00-" + traceId + "-");
    }

    @Test
    void prometheusSeesTheOutboxAndTheIdempotencyCounters() throws Exception {
        AccountResponse account = accountService.open(ALICE_ID, AccountType.SAVINGS, "THB", ALICE_ID);
        accountService.deposit(account.id(), ALICE_ID, new BigDecimal("50.00"));
        UUID paymentId = UUID.randomUUID();
        accountService.withdraw(account.id(), ALICE_ID, new BigDecimal("5.00"), paymentId);
        accountService.withdraw(account.id(), ALICE_ID, new BigDecimal("5.00"), paymentId);   // a retry

        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mockMvc.perform(get("/actuator/prometheus"))                                       // no token
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("application=\"account-service\""),
                        org.hamcrest.Matchers.containsString("banking_outbox_backlog"),
                        org.hamcrest.Matchers.containsString("banking_outbox_oldest_age_seconds"),
                        org.hamcrest.Matchers.containsString(
                                "banking_account_payment_operations_total{application=\"account-service\",kind=\"TRANSFER_OUT\",result=\"replayed\"}"))));
    }

    private ConsumerRecord<String, String> consume(String key) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("banking.account-transactions"));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (record.key().equals(key)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No event for " + key);
    }
}
