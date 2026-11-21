package com.banking.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16: what changes on Kubernetes, i.e. the "on-cloud-platform: kubernetes" document in
 * application.yml (forced here with spring.main.cloud-platform, since this JVM isn't in a pod).
 * Real ports and a plain HTTP client, because the point is WHICH PORT answers WHAT:
 * the probes on the API port without a token, the metrics only on the management port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.cloud-platform=kubernetes",
        "management.server.port=0",   // 9000 in the pod; any free port here
        "management.otlp.tracing.endpoint=http://localhost:1/v1/traces"})
@AutoConfigureObservability
class KubernetesPortsTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    void probesAnswerOnTheApiPortWithoutAToken() throws Exception {
        assertThat(get(serverPort, "/livez").statusCode()).isEqualTo(200);
        HttpResponse<String> readiness = get(serverPort, "/readyz");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("UP");
        // Probes run every few seconds in every pod: no trace and no request metric for them.
        assertThat(get(managementPort, "/actuator/prometheus").body()).doesNotContain("uri=\"/livez\"", "uri=\"/readyz\"");
    }

    @Test
    void metricsAreOnTheManagementPortOnly() throws Exception {
        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("application=\"api-gateway\"");
        assertThat(managementPort).isNotEqualTo(serverPort);
        // The API port has no Actuator any more: nothing to reach there, whatever a NetworkPolicy allows.
        assertThat(get(serverPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
