package com.banking.transactionservice.messaging;

import com.banking.transactionservice.TestcontainersConfiguration;
import com.banking.transactionservice.repository.AccountTransactionRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.banking.transactionservice.support.TestUsers.ALICE_ID;
import static com.banking.transactionservice.support.TestUsers.alice;
import static com.banking.transactionservice.support.TestUsers.bob;
import static com.banking.transactionservice.support.TestUsers.sam;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The consumer against a real broker and database: events in, statement out.
 * Events are produced as raw JSON strings, exactly as account-service sends them.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransactionHistoryIntegrationTest {

    private static final String TOPIC = "banking.account-transactions";

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private AccountTransactionRepository repository;

    @Autowired
    private WebApplicationContext context;

    /**
     * Where the TEST broker is. Not @Value("${spring.kafka.bootstrap-servers}"): @ServiceConnection
     * doesn't change that property (it would still say localhost:9092, the docker-compose broker),
     * it provides this bean, which Spring's own Kafka clients use.
     */
    @Autowired
    private KafkaConnectionDetails kafkaConnection;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static String event(UUID eventId, UUID accountId, String ownerId, String kind, String direction,
                                String amount, String balanceAfter) {
        return """
                {"eventId":"%s","eventType":"ACCOUNT_TRANSACTION","schemaVersion":1,
                 "occurredAt":"2026-11-20T10:00:00Z","accountId":"%s","accountNumber":"1234567890",
                 "ownerId":"%s","kind":"%s","direction":"%s","amount":%s,"currency":"THB",
                 "balanceAfter":%s,"paymentId":null,"someFutureField":"ignored"}"""
                .formatted(eventId, accountId, ownerId, kind, direction, amount, balanceAfter);
    }

    private void send(UUID key, String value) throws Exception {
        kafka.send(TOPIC, key.toString(), value).get();
    }

    @Test
    void eventBecomesALineOnTheOwnersStatement() throws Exception {
        UUID account = UUID.randomUUID();
        send(account, event(UUID.randomUUID(), account, ALICE_ID, "TRANSFER_IN", "CREDIT", "40.00", "140.00"));

        await().atMost(Duration.ofSeconds(15)).until(() -> repository.count() == 1);

        mockMvc.perform(get("/api/transactions").with(alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].kind").value("TRANSFER_IN"))
                .andExpect(jsonPath("$.content[0].amount").value(40.00))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(140.00));
        mockMvc.perform(get("/api/transactions").with(bob()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/transactions").param("accountId", account.toString()).with(bob()))
                .andExpect(jsonPath("$.totalElements").value(0));          // naming Alice's account doesn't help
        mockMvc.perform(get("/api/admin/transactions").param("accountId", account.toString()).with(sam()))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/admin/transactions").with(alice())).andExpect(status().isForbidden());
    }

    @Test
    void duplicateDeliveryIsStoredOnce() throws Exception {
        UUID account = UUID.randomUUID();
        String same = event(UUID.randomUUID(), account, ALICE_ID, "DEPOSIT", "CREDIT", "10.00", "10.00");
        send(account, same);
        send(account, same);
        UUID marker = UUID.randomUUID();
        send(account, event(marker, account, ALICE_ID, "DEPOSIT", "CREDIT", "1.00", "11.00"));

        // The marker comes after both copies in the same partition, so once it's stored, both were handled.
        await().atMost(Duration.ofSeconds(15)).until(() -> repository.existsById(marker));
        assertThat(repository.findByAccountId(account, Pageable.unpaged()).getTotalElements()).isEqualTo(2);
    }

    @Test
    void poisonMessageGoesToTheDeadLetterTopicAndDoesNotBlockThePartition() throws Exception {
        UUID account = UUID.randomUUID();
        try (KafkaConsumer<String, String> dlt = dltConsumer()) {
            send(account, "this is not json {");
            send(account, "{\"eventId\":null,\"amount\":5}");                  // JSON, but not a valid event
            UUID afterPoison = UUID.randomUUID();
            send(account, event(afterPoison, account, ALICE_ID, "DEPOSIT", "CREDIT", "5.00", "5.00"));

            await().atMost(Duration.ofSeconds(20)).until(() -> repository.existsById(afterPoison));

            List<ConsumerRecord<String, String>> dead = new java.util.ArrayList<>();
            await().atMost(Duration.ofSeconds(15)).until(() -> {
                dlt.poll(Duration.ofMillis(200)).forEach(dead::add);
                return dead.stream().filter(r -> r.key().equals(account.toString())).count() == 2;
            });
            ConsumerRecord<String, String> first = dead.stream().filter(r -> r.key().equals(account.toString()))
                    .findFirst().orElseThrow();
            assertThat(first.value()).isEqualTo("this is not json {");          // the ORIGINAL bytes
            assertThat(new String(first.headers().lastHeader("kafka_dlt-exception-message").value(),
                    StandardCharsets.UTF_8)).isNotBlank();                     // and why it failed
        }
    }

    private KafkaConsumer<String, String> dltConsumer() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(TOPIC + ".DLT"));
        return consumer;
    }
}
