package com.banking.paymentservice.controller;

import com.banking.paymentservice.TestcontainersConfiguration;
import com.banking.paymentservice.client.AccountClientConfig;
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
import org.springframework.beans.factory.annotation.Value;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.banking.paymentservice.support.StubAccountService.SERVICE_TOKEN;
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
 * + the real REST clients talking to a stub account-service (and stub token endpoint) over real sockets.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentIntegrationTest {

    private static final StubAccountService accountService = StubAccountService.start();

    private static final UUID SAVINGS = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String SAVINGS_NUMBER = "1000000001";
    private static final UUID CHECKING = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final String CHECKING_NUMBER = "1000000002";
    private static final UUID BOBS_ACCOUNT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");
    private static final String BOBS_NUMBER = "2000000003";

    @DynamicPropertySource
    static void accountServiceStub(DynamicPropertyRegistry registry) {
        registry.add("banking.clients.account-service.base-url", accountService::baseUrl);
        registry.add("banking.clients.account-service.read-timeout", () -> "300ms");
        registry.add("spring.security.oauth2.client.provider.keycloak.token-uri", accountService::tokenUri);
        registry.add("spring.security.oauth2.client.registration.account-service.client-secret", () -> "test-secret");
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

    @Autowired
    private OAuth2AuthorizedClientService authorizedClients;

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
        accountService.reset();
        // Alice's own accounts (visible with her token) ...
        accountService.account(SAVINGS, "THB", "ACTIVE", ALICE_TOKEN);
        accountService.account(CHECKING, "THB", "ACTIVE", ALICE_TOKEN);
        // Each test starts without a cached service token (the cache is an application-wide singleton).
        authorizedClients.removeAuthorizedClient(AccountClientConfig.REGISTRATION_ID, "payment-service");
        // ... and every account as recipient (internal API, service token).
        accountService.recipient(SAVINGS, SAVINGS_NUMBER, "THB", "ACTIVE");
        accountService.recipient(CHECKING, CHECKING_NUMBER, "THB", "ACTIVE");
        accountService.recipient(BOBS_ACCOUNT, BOBS_NUMBER, "THB", "ACTIVE");
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private ResultActions transfer(RequestPostProcessor user, UUID from, String toNumber, String amount) throws Exception {
        return mockMvc.perform(post("/api/payments").with(user).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId":"%s","targetAccountNumber":"%s","amount":%s,"description":"test"}"""
                        .formatted(from, toNumber, amount)));
    }

    private Payment onlyPayment() {
        assertThat(repository.count()).isEqualTo(1);
        return repository.findAll().getFirst();
    }

    // ------------------------------------------------------------------ success + identities

    @Test
    void aliceSendsMoneyToBobUsingTwoIdentities() throws Exception {
        transfer(alice(), SAVINGS, BOBS_NUMBER, "100.00")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/payments/")))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.payerId").value(ALICE_ID))
                .andExpect(jsonPath("$.targetAccountNumber").value(BOBS_NUMBER))
                .andExpect(jsonPath("$.targetAccountId").value(BOBS_ACCOUNT.toString()))
                .andExpect(jsonPath("$.currency").value("THB"));

        var calls = accountService.accountRequests();
        assertThat(calls).extracting(StubAccountService.Recorded::call).containsExactly(
                "GET /api/accounts/" + SAVINGS,
                "GET /internal/accounts/by-number/" + BOBS_NUMBER,
                "POST /api/accounts/" + SAVINGS + "/withdraw",
                "POST /internal/accounts/" + BOBS_ACCOUNT + "/credit");
        assertThat(calls).extracting(StubAccountService.Recorded::authorization).containsExactly(
                "Bearer " + ALICE_TOKEN,      // her account: account-service checks it's hers
                "Bearer " + SERVICE_TOKEN,    // any recipient: payment-service's permission
                "Bearer " + ALICE_TOKEN,      // money leaves HER account: her consent
                "Bearer " + SERVICE_TOKEN);   // money arrives in Bob's: payment-service's permission
        Payment payment = onlyPayment();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(calls.getLast().body()).contains("\"paymentId\":\"" + payment.getId() + "\"");
    }

    @Test
    void serviceTokenIsReusedAcrossPayments() throws Exception {
        transfer(alice(), SAVINGS, BOBS_NUMBER, "1.00").andExpect(status().isCreated());
        transfer(alice(), SAVINGS, CHECKING_NUMBER, "1.00").andExpect(status().isCreated());

        assertThat(accountService.tokenRequests()).isEqualTo(1);
    }

    @Test
    void outcomeIsPublishedToKafkaKeyedByPayment() throws Exception {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            String body = transfer(alice(), SAVINGS, BOBS_NUMBER, "7.00").andReturn().getResponse().getContentAsString();
            String paymentId = objectMapper.readTree(body).path("id").asText();

            consumer.subscribe(List.of("banking.payment-events"));
            JsonNode event = null;
            long deadline = System.currentTimeMillis() + 15_000;
            while (event == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (record.key().equals(paymentId)) {
                        event = objectMapper.readTree(record.value());
                    }
                }
            }
            assertThat(event).isNotNull();
            assertThat(event.path("eventType").asText()).isEqualTo("PAYMENT_COMPLETED");
            assertThat(event.path("payerId").asText()).isEqualTo(ALICE_ID);
            assertThat(event.path("targetAccountNumber").asText()).isEqualTo(BOBS_NUMBER);
        }
        // The debit carried the payment ID, so account-service labels it TRANSFER_OUT.
        assertThat(accountService.accountRequests().get(2).body()).contains("\"paymentId\"");
    }

    // ------------------------------------------------------------------ refused: nothing moved

    @Test
    void unknownAccountNumberIsRejected() throws Exception {
        transfer(alice(), SAVINGS, "9999999999", "10.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TARGET_ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.paymentStatus").value("REJECTED"))
                .andExpect(jsonPath("$.paymentId").exists());

        assertThat(accountService.calls()).noneMatch(call -> call.startsWith("POST"));
    }

    @Test
    void bobCannotPayFromAlicesAccountEvenThoughTheServiceCouldCredit() throws Exception {
        // Bob's token can't see Alice's account: the debit side still needs the OWNER's token.
        transfer(bob(), SAVINGS, BOBS_NUMBER, "50.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SOURCE_ACCOUNT_NOT_FOUND"));
        assertThat(accountService.calls()).noneMatch(call -> call.startsWith("POST"));
    }

    @Test
    void ownSourceNumberAsTargetIsRejected() throws Exception {
        transfer(alice(), SAVINGS, SAVINGS_NUMBER, "10.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));
    }

    @Test
    void insufficientFundsComesFromAccountService() throws Exception {
        accountService.problem("POST", "/api/accounts/" + SAVINGS + "/withdraw", 422,
                "INSUFFICIENT_FUNDS", "Account has insufficient funds: balance 5.00, requested 10.00");

        transfer(alice(), SAVINGS, BOBS_NUMBER, "10.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.detail").value(startsWith("Account has insufficient funds")));

        assertThat(onlyPayment().getStatus()).isEqualTo(PaymentStatus.REJECTED);
    }

    @Test
    void accountServiceErrorBeforeTheDebitIs503WithRetryAfter() throws Exception {
        accountService.problem("GET", "/api/accounts/" + SAVINGS, 503, null, "unavailable");

        transfer(alice(), SAVINGS, BOBS_NUMBER, "10.00")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"));
    }

    @Test
    void keycloakRefusingPaymentServiceIs503AndNothingMoves() throws Exception {
        accountService.tokenEndpointRefuses();

        transfer(alice(), SAVINGS, BOBS_NUMBER, "10.00")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.paymentStatus").value("FAILED"));
        assertThat(accountService.calls()).noneMatch(call -> call.startsWith("POST"));
    }

    @Test
    void cachedServiceTokenKeepsPaymentsWorkingWhileKeycloakIsDown() throws Exception {
        transfer(alice(), SAVINGS, BOBS_NUMBER, "1.00").andExpect(status().isCreated());   // token fetched
        accountService.tokenEndpointRefuses();                                          // Keycloak "down"

        transfer(alice(), SAVINGS, BOBS_NUMBER, "1.00").andExpect(status().isCreated());   // cached token
        assertThat(accountService.tokenRequests()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ failures in the middle

    @Test
    void creditRefusedAfterDebitIsReversedWithTheServiceToken() throws Exception {
        accountService.problem("POST", "/internal/accounts/" + BOBS_ACCOUNT + "/credit", 422,
                "ACCOUNT_NOT_ACTIVE", "Account is FROZEN");

        transfer(alice(), SAVINGS, BOBS_NUMBER, "10.00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.paymentStatus").value("REVERSED"))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        assertThat(accountService.calls()).endsWith(
                "POST /api/accounts/" + SAVINGS + "/withdraw",
                "POST /internal/accounts/" + BOBS_ACCOUNT + "/credit",
                "POST /internal/accounts/" + SAVINGS + "/credit");   // refund (body: "refund":true)
    }

    @Test
    void debitWithoutAnswerIsAcceptedForReconciliationNotReportedAsFailed() throws Exception {
        accountService.slowReply("POST", "/api/accounts/" + SAVINGS + "/withdraw", Duration.ofSeconds(2));

        transfer(alice(), SAVINGS, BOBS_NUMBER, "10.00")
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
    void targetMustBeATenDigitAccountNumber() throws Exception {
        transfer(alice(), SAVINGS, "12345", "10.00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.targetAccountNumber").exists());
        assertThat(accountService.requests()).isEmpty();
        assertThat(repository.count()).isZero();
    }

    @Test
    void amountMustBePositiveWithTwoDecimals() throws Exception {
        transfer(alice(), SAVINGS, BOBS_NUMBER, "-5").andExpect(status().isBadRequest());
        transfer(alice(), SAVINGS, BOBS_NUMBER, "1.005").andExpect(status().isBadRequest());
        assertThat(accountService.requests()).isEmpty();
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void customersSeeOnlyTheirOwnPayments() throws Exception {
        String body = transfer(alice(), SAVINGS, BOBS_NUMBER, "1.00").andReturn().getResponse().getContentAsString();
        String paymentId = objectMapper.readTree(body).path("id").asText();

        mockMvc.perform(get("/api/payments/{id}", paymentId).with(alice())).andExpect(status().isOk());
        // Bob RECEIVED it, but it's Alice's payment record (incoming history comes with Phase 11).
        mockMvc.perform(get("/api/payments/{id}", paymentId).with(bob())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/payments").with(bob()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void staffCanReadButNotPay() throws Exception {
        transfer(sam(), SAVINGS, BOBS_NUMBER, "1.00").andExpect(status().isForbidden());
        transfer(ada(), SAVINGS, BOBS_NUMBER, "1.00").andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/payments").with(ada())).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/payments").with(alice())).andExpect(status().isForbidden());
        assertThat(accountService.requests()).isEmpty();
    }

    @Test
    void noTokenIs401() throws Exception {
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void paymentListIsNewestFirst() throws Exception {
        transfer(alice(), SAVINGS, BOBS_NUMBER, "1.00");
        transfer(alice(), SAVINGS, BOBS_NUMBER, "2.00");

        JsonNode page = objectMapper.readTree(mockMvc.perform(get("/api/payments").with(alice()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.path("content").get(0).path("amount").decimalValue()).isEqualByComparingTo("2.00");
    }
}
