package com.banking.userservice.events;

import com.banking.userservice.TestcontainersConfiguration;
import com.banking.userservice.dto.AddressDto;
import com.banking.userservice.dto.CustomerProfileRequest;
import com.banking.userservice.dto.CustomerResponse;
import com.banking.userservice.repository.CustomerRepository;
import com.banking.userservice.service.CustomerService;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Profile changes become compacted-topic records keyed by identity; deletion becomes a tombstone. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CustomerEventsIntegrationTest {

    private static final String IDENTITY = "11111111-2222-3333-4444-555555555555";

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Where the TEST broker is. Not @Value("${spring.kafka.bootstrap-servers}"): @ServiceConnection
     * doesn't change that property (it would still say localhost:9092, the docker-compose broker),
     * it provides this bean, which Spring's own Kafka clients use.
     */
    @Autowired
    private KafkaConnectionDetails kafkaConnection;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        customerRepository.deleteAll();
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of("banking.customer-events"));
    }

    @AfterEach
    void close() {
        consumer.close();
    }

    @Test
    void createUpdateDeletePublishesCurrentStateThenATombstone() throws Exception {
        CustomerProfileRequest profile = new CustomerProfileRequest("Carol", "Customer", "Carol@Example.com", null,
                new AddressDto("1 Road", "Bangkok", "10500", "TH"));
        CustomerResponse created = customerService.createForIdentity(IDENTITY, profile);
        customerService.updateByKeycloakUserId(IDENTITY, new CustomerProfileRequest("Carol", "Married", "carol@example.com",
                null, profile.address()));
        customerService.delete(created.id(), "admin");

        // Other test classes share this broker and publish their own customers: keep only ours.
        List<ConsumerRecord<String, String>> records = poll(3);
        assertThat(records).hasSize(3);
        JsonNode first = objectMapper.readTree(records.get(0).value());
        assertThat(first.path("eventType").asText()).isEqualTo("CUSTOMER_UPSERTED");
        assertThat(first.path("email").asText()).isEqualTo("carol@example.com");   // normalized, as stored
        assertThat(first.has("phoneNumber")).isFalse();                            // not broadcast
        assertThat(objectMapper.readTree(records.get(1).value()).path("lastName").asText()).isEqualTo("Married");
        assertThat(records.get(2).value()).isNull();                                // tombstone
    }

    private List<ConsumerRecord<String, String>> poll(int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (records.size() < expected && System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                if (IDENTITY.equals(record.key())) {
                    records.add(record);
                }
            }
        }
        return records;
    }
}
