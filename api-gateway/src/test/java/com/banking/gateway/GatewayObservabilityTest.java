package com.banking.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Phase 14, with tracing and metrics export switched ON (Spring Boot switches them off in tests
 * unless asked): a real port, so the request goes through WebFlux's server observation exactly
 * like in production. The OTLP exporter has nowhere to send to here; that only logs a warning.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class GatewayObservabilityTest {

    private static final AtomicReference<String> traceparentSeenByBackend = new AtomicReference<>();
    private static DisposableServer backend;

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        backend = HttpServer.create().host("localhost").port(0)
                .route(routes -> routes.get("/api/accounts", (req, res) -> {
                    traceparentSeenByBackend.set(req.requestHeaders().get("traceparent"));
                    return res.header("Content-Type", "application/json").sendString(Mono.just("[]"));
                }))
                .bindNow();
        String url = "http://localhost:" + backend.port();
        registry.add("USER_SERVICE_URL", () -> url);
        registry.add("ACCOUNT_SERVICE_URL", () -> url);
        registry.add("PAYMENT_SERVICE_URL", () -> url);
        registry.add("management.otlp.tracing.endpoint", () -> "http://localhost:1/v1/traces");
    }

    @AfterAll
    static void stop() {
        backend.disposeNow();
    }

    @LocalServerPort
    private int port;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void theTraceIdGivenToTheClientIsTheTraceTheServiceReceives() {
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.just(Jwt.withTokenValue("t").header("alg", "RS256")
                .subject("alice").audience(List.of("banking-api")).claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build()));

        String traceId = client().get().uri("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer t").exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Trace-Id");

        assertThat(traceId).matches("[0-9a-f]{32}");
        // W3C traceparent: 00-<trace id>-<parent span id>-<flags>. Same trace, one hop further.
        assertThat(traceparentSeenByBackend.get()).startsWith("00-" + traceId + "-");
    }

    @Test
    void errorsCarryATraceIdToo() {
        client().get().uri("/api/accounts").exchange()          // no token: 401 from the gateway itself
                .expectStatus().isUnauthorized()
                .expectHeader().value("X-Trace-Id", id -> assertThat(id).matches("[0-9a-f]{32}"));
    }

    @Test
    void prometheusCanScrapeWithoutAToken() {
        client().get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body)
                        .contains("application=\"api-gateway\"")
                        .contains("jvm_memory_used_bytes"));
    }

    @Test
    void scrapesAndProbesAreNotTraced() {
        client().get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("X-Trace-Id");
    }

    @Test
    void otherActuatorEndpointsStillNeedAdmin() {
        client().get().uri("/actuator/gateway/routes").exchange().expectStatus().isUnauthorized();
    }
}
