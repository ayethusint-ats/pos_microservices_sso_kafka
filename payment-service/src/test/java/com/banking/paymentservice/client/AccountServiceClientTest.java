package com.banking.paymentservice.client;

import com.banking.paymentservice.support.StubAccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP client exactly as production builds it, against a stub on a real socket.
 * Focus: token relay, and turning every kind of failure into "refused", "didn't happen" or "unknown".
 */
class AccountServiceClientTest {

    private static final StubAccountService stub = StubAccountService.start();
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private AccountServiceClient client;

    @BeforeEach
    void setUp() {
        stub.reset();
        client = clientFor(stub.baseUrl());
        Jwt jwt = Jwt.withTokenValue("alice-access-token").header("alg", "RS256").subject("alice").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @Test
    void relaysTheCallersTokenAndReadsTheAccount() {
        stub.account(ACCOUNT, "THB", "ACTIVE");

        AccountView account = client.getAccount(ACCOUNT);

        assertThat(account.currency()).isEqualTo("THB");
        assertThat(account.isActive()).isTrue();
        assertThat(stub.requests().getFirst().authorization()).isEqualTo("Bearer alice-access-token");
    }

    @Test
    void sendsTheAmountAsJson() {
        stub.account(ACCOUNT, "THB", "ACTIVE");

        client.debit(ACCOUNT, new BigDecimal("12.50"));

        assertThat(stub.requests().getFirst().call()).isEqualTo("POST /api/accounts/" + ACCOUNT + "/withdraw");
        assertThat(stub.requests().getFirst().body()).isEqualTo("{\"amount\":12.50}");
    }

    @Test
    void businessRefusalKeepsAccountServicesErrorCode() {
        stub.problem("POST", "/api/accounts/" + ACCOUNT + "/withdraw", 422, "INSUFFICIENT_FUNDS", "balance 5.00");

        assertThatThrownBy(() -> client.debit(ACCOUNT, BigDecimal.TEN))
                .isInstanceOfSatisfying(AccountRejectedException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_FUNDS");
                    assertThat(ex.getMessage()).isEqualTo("balance 5.00");
                });
    }

    @Test
    void notFoundIsARefusal() {
        assertThatThrownBy(() -> client.getAccount(ACCOUNT))
                .isInstanceOfSatisfying(AccountRejectedException.class, ex -> assertThat(ex.isNotFound()).isTrue());
    }

    @Test
    void serverErrorMeansOutcomeUnknown() {
        stub.problem("POST", "/api/accounts/" + ACCOUNT + "/withdraw", 500, null, "boom");

        assertThatThrownBy(() -> client.debit(ACCOUNT, BigDecimal.TEN))
                .isInstanceOfSatisfying(AccountServiceUnavailableException.class,
                        ex -> assertThat(ex.isOutcomeUnknown()).isTrue());
    }

    @Test
    void rejectedTokenMeansNothingHappened() {
        stub.problem("POST", "/api/accounts/" + ACCOUNT + "/withdraw", 401, null, "token expired");

        assertThatThrownBy(() -> client.debit(ACCOUNT, BigDecimal.TEN))
                .isInstanceOfSatisfying(AccountServiceUnavailableException.class,
                        ex -> assertThat(ex.isOutcomeUnknown()).isFalse());
    }

    @Test
    void responseTimeoutMeansOutcomeUnknown() {
        stub.account(ACCOUNT, "THB", "ACTIVE");
        stub.slowReply("POST", "/api/accounts/" + ACCOUNT + "/withdraw", Duration.ofSeconds(2));

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.debit(ACCOUNT, BigDecimal.TEN))
                .isInstanceOfSatisfying(AccountServiceUnavailableException.class,
                        ex -> assertThat(ex.isOutcomeUnknown()).isTrue());
        // The request DID reach account-service: it was recorded there. We just never got the answer.
        assertThat(stub.calls()).containsExactly("POST /api/accounts/" + ACCOUNT + "/withdraw");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void connectionRefusedMeansNothingHappened() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        AccountServiceClient unreachable = clientFor("http://localhost:" + closedPort);

        assertThatThrownBy(() -> unreachable.debit(ACCOUNT, BigDecimal.TEN))
                .isInstanceOfSatisfying(AccountServiceUnavailableException.class,
                        ex -> assertThat(ex.isOutcomeUnknown()).isFalse());
    }

    private static AccountServiceClient clientFor(String baseUrl) {
        AccountServiceProperties properties = new AccountServiceProperties(
                URI.create(baseUrl), Duration.ofMillis(500), Duration.ofMillis(300));
        AccountApi api = AccountClientConfig.createAccountApi(RestClient.builder(), properties);
        return new AccountServiceClient(api, new ObjectMapper());
    }
}
