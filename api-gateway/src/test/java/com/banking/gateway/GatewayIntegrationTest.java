package com.banking.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * The whole gateway (security, CORS, rate limiting, routing, error mapping) against a real HTTP
 * "backend" started on a random port. It plays user-service and account-service.
 *
 * <p>Only the JWT decoder is mocked: tokens are named, e.g. {@code valid-alice},
 * {@code valid-ada.ADMIN}, {@code forged}, {@code idp-down}. Real signature checks were
 * verified against Keycloak in Phase 5.
 */
@SpringBootTest(properties = {
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s",
        "banking.gateway.rate-limit.replenish-rate=1",
        "banking.gateway.rate-limit.burst-capacity=3"
})
@Import(GatewayIntegrationTest.UnreachableRouteConfig.class)
class GatewayIntegrationTest {

    private static DisposableServer backend;

    @DynamicPropertySource
    static void pointRoutesAtFakeBackend(DynamicPropertyRegistry registry) {
        backend = HttpServer.create().host("localhost").port(0)
                .route(routes -> routes
                        .get("/api/whoami", (req, res) -> res.header("Content-Type", "application/json")
                                .sendString(Mono.just(echo(req))))
                        .get("/api/accounts", (req, res) -> res.header("Content-Type", "application/json")
                                .sendString(Mono.just("[]")))
                        .get("/api/admin/accounts", (req, res) -> res.header("Content-Type", "application/json")
                                .sendString(Mono.just("[]")))
                        .get("/api/accounts/slow", (req, res) -> res.sendString(
                                Mono.delay(Duration.ofSeconds(3)).thenReturn("too late"))))
                .bindNow();
        String url = "http://localhost:" + backend.port();
        registry.add("USER_SERVICE_URL", () -> url);
        registry.add("ACCOUNT_SERVICE_URL", () -> url);
    }

    @AfterAll
    static void stopBackend() {
        backend.disposeNow();
    }

    /** Extra route to a closed port: what clients get when a service is down. (DNS failures: GatewayErrorHandlerTest.) */
    @TestConfiguration
    static class UnreachableRouteConfig {
        @Bean
        RouteLocator unreachableRoutes(RouteLocatorBuilder builder) throws IOException {
            int closedPort;
            try (ServerSocket socket = new ServerSocket(0)) {   // grab a free port, then release it
                closedPort = socket.getLocalPort();
            }
            int port = closedPort;
            return builder.routes()
                    .route("service-down", r -> r.path("/api/down/**").uri("http://127.0.0.1:" + port))
                    .build();
        }
    }

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @Autowired
    private ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> decode(invocation.getArgument(0)));
        client = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                // Absolute URL like a real request. Relative URLs have no host, which breaks
                // CORS same-origin detection and X-Forwarded-Host in the test harness.
                .baseUrl("http://api.bank.local")
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ---------- authentication at the edge ----------

    @Test
    void requestWithoutTokenIsRejectedAtTheGateway() {
        client.get().uri("/api/whoami").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.title").isEqualTo("Unauthorized")
                .jsonPath("$.requestId").exists();
    }

    @Test
    void forgedTokenIsRejectedWithInvalidToken() {
        client.get().uri("/api/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer forged").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, v -> assertThat(v).contains("invalid_token"));
    }

    @Test
    void identityProviderDownGives503NotLogout() {
        client.get().uri("/api/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer idp-down").exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10");
    }

    // ---------- routing, token relay, correlation id ----------

    @Test
    void validRequestIsRoutedWithTokenAndRequestIdRelayed() {
        client.get().uri("/api/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer valid-alice").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.authorization").isEqualTo("Bearer valid-alice")   // service receives the same token
                .jsonPath("$.requestId").value(id -> assertThat((String) id).hasSize(36))
                // No trusted-proxies configured → the gateway adds no X-Forwarded-* headers
                // (Spring Cloud Gateway 2025.0 default, prevents header spoofing).
                .jsonPath("$.forwardedFor").doesNotExist();
    }

    @Test
    void safeClientRequestIdIsReusedUnsafeOneIsReplaced() {
        client.get().uri("/api/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer valid-reqid")
                .header("X-Request-Id", "my-trace-12345").exchange()
                .expectHeader().valueEquals("X-Request-Id", "my-trace-12345");

        client.get().uri("/api/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer valid-reqid2")
                .header("X-Request-Id", "evil\"value with spaces").exchange()
                .expectHeader().value("X-Request-Id", v -> assertThat(v).hasSize(36));
    }

    @Test
    void unknownApiPathIs404() {
        client.get().uri("/api/does-not-exist").header(HttpHeaders.AUTHORIZATION, "Bearer valid-lost").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith("application/problem+json");
    }

    @Test
    void pathsOutsideApiAreDeniedEvenWithValidToken() {
        client.get().uri("/internal/secrets").header(HttpHeaders.AUTHORIZATION, "Bearer valid-curious").exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void backOfficeIsStaffOnlyAtTheEdge() {
        client.get().uri("/api/admin/accounts?ownerId=x").header(HttpHeaders.AUTHORIZATION, "Bearer valid-nosy").exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.title").isEqualTo("Forbidden");
        client.get().uri("/api/admin/accounts?ownerId=x").header(HttpHeaders.AUTHORIZATION, "Bearer valid-sam.SUPPORT").exchange()
                .expectStatus().isOk();
    }

    // ---------- downstream failures ----------

    @Test
    void slowServiceGives504() {
        client.get().uri("/api/accounts/slow").header(HttpHeaders.AUTHORIZATION, "Bearer valid-patient").exchange()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
                .expectBody().jsonPath("$.title").isEqualTo("Gateway timeout");
    }

    @Test
    void serviceDownGives502() {
        client.get().uri("/api/down/x").header(HttpHeaders.AUTHORIZATION, "Bearer valid-unlucky").exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectBody().jsonPath("$.title").isEqualTo("Bad gateway");
    }


    // ---------- rate limiting ----------

    @Test
    void fourthRequestInABurstOfThreeIsRateLimited() {
        for (int i = 0; i < 3; i++) {
            client.get().uri("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer valid-greedy").exchange()
                    .expectStatus().isOk()
                    .expectHeader().exists("X-RateLimit-Remaining");
        }
        client.get().uri("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer valid-greedy").exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0");

        // Budgets are per user: someone else is unaffected.
        client.get().uri("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer valid-modest").exchange()
                .expectStatus().isOk();
    }

    // ---------- CORS ----------

    @Test
    void corsPreflightFromAngularIsAllowed() {
        client.options().uri("/api/accounts")
                .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, v -> assertThat(v).contains("POST"));
    }

    @Test
    void corsPreflightFromUnknownOriginIsRejected() {
        client.options().uri("/api/accounts")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    // ---------- actuator ----------

    @Test
    void healthIsPublicAndRoutesAreAdminOnly() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        client.get().uri("/actuator/gateway/routes").header(HttpHeaders.AUTHORIZATION, "Bearer valid-alice2").exchange()
                .expectStatus().isForbidden();
        client.get().uri("/actuator/gateway/routes").header(HttpHeaders.AUTHORIZATION, "Bearer valid-ada.ADMIN").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[?(@.route_id=='account-service')]").exists();
    }

    // ---------- helpers ----------

    /** "valid-<sub>[.ROLE]" → a Jwt; "idp-down" → infrastructure error; anything else → bad signature. */
    private static Mono<Jwt> decode(String token) {
        if (token.equals("idp-down")) {
            return Mono.error(new JwtException("Couldn't retrieve remote JWK set: Connection refused"));
        }
        if (!token.startsWith("valid-")) {
            return Mono.error(new BadJwtException("Signed JWT rejected: Invalid signature"));
        }
        String[] parts = token.substring("valid-".length()).split("\\.");
        List<String> roles = parts.length > 1 ? List.of(parts[1]) : List.of("CUSTOMER");
        return Mono.just(Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(parts[0])
                .audience(List.of("banking-api"))
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build());
    }

    private static String echo(HttpServerRequest request) {
        return "{\"authorization\":%s,\"requestId\":%s,\"forwardedFor\":%s}".formatted(
                quote(request.requestHeaders().get(HttpHeaders.AUTHORIZATION)),
                quote(request.requestHeaders().get("X-Request-Id")),
                quote(request.requestHeaders().get("X-Forwarded-For")));
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
