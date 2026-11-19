package com.banking.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Writes RFC 9457 problem+json responses from the gateway, so a client sees the SAME error format
 * whether the error came from the gateway (401, 429, 502, 504) or from a service (400, 404, 422).
 */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static Mono<Void> write(ServerWebExchange exchange, ObjectMapper objectMapper,
                                   HttpStatusCode status, String title, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
        String requestId = exchange.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);   // quote this ID when reporting a problem
        }

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(problem);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().setContentLength(body.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
