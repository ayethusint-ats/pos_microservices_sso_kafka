package com.banking.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** Each proxying failure maps to the right status, independent of network timing. */
class GatewayErrorHandlerTest {

    private final GatewayErrorHandler handler = new GatewayErrorHandler(new ObjectMapper());

    @Test
    void connectionRefusedIs502() {
        assertThat(statusFor(new RuntimeException("wrapped", new ConnectException("Connection refused"))))
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void dnsFailureIs502() {
        // e.g. a Kubernetes Service that doesn't exist: "Failed to resolve 'account-service'"
        assertThat(statusFor(new UnknownHostException("Failed to resolve 'account-service'")))
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void responseTimeoutIs504() {
        assertThat(statusFor(new TimeoutException("Response took longer than timeout: PT5S")))
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void notFoundKeepsItsStatus() {
        assertThat(statusFor(new ResponseStatusException(HttpStatus.NOT_FOUND))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unexpectedErrorIs500WithoutLeakingDetails() {
        MockServerWebExchange exchange = exchange();
        handler.handle(exchange, new IllegalStateException("secret internal detail")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("secret internal detail");
    }

    private HttpStatus statusFor(Throwable error) {
        MockServerWebExchange exchange = exchange();
        handler.handle(exchange, error).block();
        return HttpStatus.valueOf(exchange.getResponse().getStatusCode().value());
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("http://api.bank.local/api/accounts").build());
    }
}
