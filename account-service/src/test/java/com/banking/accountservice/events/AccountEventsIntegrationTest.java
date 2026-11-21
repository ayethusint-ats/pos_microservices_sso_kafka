package com.banking.accountservice.events;

import com.banking.accountservice.TestcontainersConfiguration;
import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.exception.InsufficientFundsException;
import com.banking.accountservice.service.AccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.banking.accountservice.support.TestUsers.ADA_ID;
import static com.banking.accountservice.support.TestUsers.ALICE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Committed balance changes become Kafka events; rolled-back ones don't. Real broker (Testcontainers). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountEventsIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Where the TEST broker is. Not @Value("${spring.kafka.bootstrap-servers}"): @ServiceConnection
     * doesn't change that property (it would still say localhost:9092, the docker-compose broker),
     * it provides this bean, which Spring's own Kafka clients use.
     */
    @Autowired
    private KafkaConnectionDetails kafkaConnection;

    @Value("${banking.kafka.topics.account-transactions}")
    private String topic;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void subscribe() {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
        // Join the group and get partitions assigned BEFORE producing, so "latest" means "from now".
        while (consumer.assignment().isEmpty()) {
            consumer.poll(Duration.ofMillis(200));
        }
    }

    @AfterEach
    void close() {
        consumer.close();
    }

    @Test
    void committedChangesArePublishedKeyedByAccountInOrder() throws Exception {
        AccountResponse account = accountService.open(ALICE_ID, AccountType.SAVINGS, "THB", ALICE_ID);
        accountService.deposit(account.id(), ALICE_ID, new BigDecimal("100"));
        UUID paymentId = UUID.randomUUID();
        accountService.withdraw(account.id(), ALICE_ID, new BigDecimal("40"), paymentId);

        List<ConsumerRecord<String, String>> records = poll(2, account.id());

        assertThat(records).extracting(ConsumerRecord::key).containsOnly(account.id().toString());
        JsonNode deposit = objectMapper.readTree(records.get(0).value());
        JsonNode debit = objectMapper.readTree(records.get(1).value());
        assertThat(deposit.path("kind").asText()).isEqualTo("DEPOSIT");
        assertThat(deposit.path("balanceAfter").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(debit.path("kind").asText()).isEqualTo("TRANSFER_OUT");
        assertThat(debit.path("direction").asText()).isEqualTo("DEBIT");
        assertThat(debit.path("paymentId").asText()).isEqualTo(paymentId.toString());
        assertThat(debit.path("ownerId").asText()).isEqualTo(ALICE_ID);
        assertThat(debit.path("balanceAfter").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(debit.path("occurredAt").asText()).endsWith("Z");             // ISO-8601, not epoch numbers
        assertThat(debit.path("schemaVersion").asInt()).isEqualTo(1);
    }

    @Test
    void refusedChangesPublishNothing() {
        AccountResponse account = accountService.open(ALICE_ID, AccountType.SAVINGS, "THB", ADA_ID);

        assertThatThrownBy(() -> accountService.withdraw(account.id(), ALICE_ID, new BigDecimal("1")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(poll(0, account.id())).isEmpty();
    }

    @Test
    void refundIsLabelledAsSuch() throws Exception {
        AccountResponse account = accountService.open(ALICE_ID, AccountType.SAVINGS, "THB", ALICE_ID);
        accountService.deposit(account.id(), ALICE_ID, new BigDecimal("10"));
        UUID paymentId = UUID.randomUUID();
        accountService.withdraw(account.id(), ALICE_ID, new BigDecimal("5"), paymentId);   // Phase 13: refund needs a debit
        accountService.creditForPayment(account.id(), new BigDecimal("5"), paymentId, true, "payment-service");

        List<ConsumerRecord<String, String>> records = poll(3, account.id());
        assertThat(records).extracting(r -> objectMapper.readTree(r.value()).path("kind").asText())
                .containsExactly("DEPOSIT", "TRANSFER_OUT", "REFUND");
    }

    /**
     * Polls until {@code expected} records OF THIS ACCOUNT arrived (or 3 s for "expect none").
     * Filtered by key: since Phase 13 the outbox relay may still deliver other tests' events.
     */
    private List<ConsumerRecord<String, String>> poll(int expected, UUID accountId) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.currentTimeMillis() + (expected == 0 ? 3_000 : 15_000);
        while (System.currentTimeMillis() < deadline && (expected == 0 || records.size() < expected)) {
            consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                if (r.key().equals(accountId.toString())) {
                    records.add(r);
                }
            });
        }
        return records;
    }
}
