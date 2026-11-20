package com.banking.notificationservice.messaging;

import com.banking.notificationservice.TestcontainersConfiguration;
import com.banking.notificationservice.notification.CustomerDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.UUID;

import static com.banking.notificationservice.support.TestUsers.ALICE_ID;
import static com.banking.notificationservice.support.TestUsers.BOB_ID;
import static com.banking.notificationservice.support.TestUsers.alice;
import static com.banking.notificationservice.support.TestUsers.bob;
import static com.banking.notificationservice.support.TestUsers.sam;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Events in (exactly as the producers send them), simulated e-mails out. Real broker.
 * Note: a JSONPath filter ({@code $[?(...)].body}) returns a LIST, hence {@code hasItem(...)}.
 * auto-offset-reset=earliest: in production a new group starts at "latest" (no mails about old
 * payments), but here the test produces right after startup and must not miss its own events.
 */
@SpringBootTest(properties = "spring.kafka.consumer.auto-offset-reset=earliest")
@Import(TestcontainersConfiguration.class)
class NotificationIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private CustomerDirectory directory;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        customer(BOB_ID, "Bob", "bob@example.com");
        await().atMost(Duration.ofSeconds(20)).until(() -> directory.find(BOB_ID).isPresent());
    }

    private void customer(String id, String firstName, String email) throws Exception {
        kafka.send("banking.customer-events", id, """
                {"eventType":"CUSTOMER_UPSERTED","schemaVersion":1,"occurredAt":"2026-11-20T10:00:00Z",
                 "keycloakUserId":"%s","firstName":"%s","lastName":"Customer","email":"%s"}"""
                .formatted(id, firstName, email)).get();
    }

    private String transferIn(UUID eventId, String ownerId) {
        return """
                {"eventId":"%s","eventType":"ACCOUNT_TRANSACTION","schemaVersion":1,"occurredAt":"2026-11-20T10:00:00Z",
                 "accountId":"%s","accountNumber":"2000000003","ownerId":"%s","kind":"TRANSFER_IN",
                 "direction":"CREDIT","amount":40.00,"currency":"THB","balanceAfter":40.00,"paymentId":"%s"}"""
                .formatted(eventId, UUID.randomUUID(), ownerId, UUID.randomUUID());
    }

    @Test
    void recipientIsToldMoneyArrivedAtTheAddressFromTheDirectory() throws Exception {
        UUID event = UUID.randomUUID();
        kafka.send("banking.account-transactions", "k", transferIn(event, BOB_ID)).get();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mockMvc.perform(get("/api/notifications").with(bob()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[?(@.sourceEventId=='%s')].subject".formatted(event)).value("Money received"))
                        .andExpect(jsonPath("$[?(@.sourceEventId=='%s')].to".formatted(event)).value("bob@example.com"))
                        .andExpect(jsonPath("$[?(@.sourceEventId=='%s')].body".formatted(event))
                                .value(hasItem(startsWith("Hi Bob, You received 40.00 THB in account 2000000003")))));
        // Alice can't read Bob's mails.
        mockMvc.perform(get("/api/notifications").with(alice()))
                .andExpect(jsonPath("$[?(@.sourceEventId=='%s')]".formatted(event)).isEmpty());
    }

    @Test
    void payerIsToldAboutTheOutcomeAndDuplicatesMailOnce() throws Exception {
        UUID event = UUID.randomUUID();
        String completed = """
                {"eventId":"%s","eventType":"PAYMENT_COMPLETED","schemaVersion":1,"occurredAt":"2026-11-20T10:00:00Z",
                 "paymentId":"%s","payerId":"%s","sourceAccountId":"%s","targetAccountNumber":"2000000003",
                 "amount":40.00,"currency":"THB","status":"COMPLETED","failureCode":null}"""
                .formatted(event, UUID.randomUUID(), ALICE_ID, UUID.randomUUID());
        kafka.send("banking.payment-events", "p", completed).get();
        kafka.send("banking.payment-events", "p", completed).get();   // redelivery

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mockMvc.perform(get("/api/notifications").with(alice()))
                        .andExpect(jsonPath("$[?(@.sourceEventId=='%s')].body".formatted(event))
                                .value(hasItem(containsString("You sent 40.00 THB to account 2000000003")))));
        Thread.sleep(1000);   // give the duplicate time to arrive
        mockMvc.perform(get("/api/notifications").with(alice()))
                .andExpect(jsonPath("$[?(@.sourceEventId=='%s')]".formatted(event)).value(org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void tombstoneForgetsTheCustomer() throws Exception {
        String carol = UUID.randomUUID().toString();
        customer(carol, "Carol", "carol@example.com");
        await().atMost(Duration.ofSeconds(15)).until(() -> directory.find(carol).isPresent());

        kafka.send("banking.customer-events", carol, null).get();   // profile deleted

        await().atMost(Duration.ofSeconds(15)).until(() -> directory.find(carol).isEmpty());
    }

    @Test
    void staffHaveNoInbox() throws Exception {
        mockMvc.perform(get("/api/notifications").with(sam())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }
}
