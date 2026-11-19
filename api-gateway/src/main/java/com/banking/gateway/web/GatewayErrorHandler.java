package com.banking.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Turns failures while proxying into clear problem+json responses.
 *
 * <ul>
 *   <li>Can't reach the service: connection refused, connect timeout, or DNS can't resolve the
 *       host name (e.g. a missing Kubernetes Service) → <b>502 Bad Gateway</b></li>
 *   <li>Service connected but didn't answer in time → <b>504 Gateway Timeout</b></li>
 *   <li>No route / other HTTP status errors → that status (e.g. 404)</li>
 *   <li>Anything else → 500, details only in the log</li>
 * </ul>
 * Order -2 runs before Spring Boot's default handler (-1), which would return its own JSON shape.
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ObjectMapper objectMapper;

    public GatewayErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        String path = exchange.getRequest().getPath().value();

        if (hasCause(ex, ConnectException.class) || hasCause(ex, UnknownHostException.class)) {
            log.warn("Downstream unreachable for {}: {}", path, rootMessage(ex));
            return ProblemResponses.write(exchange, objectMapper, HttpStatus.BAD_GATEWAY,
                    "Bad gateway", "The downstream service is unavailable. Please retry later.");
        }
        if (hasCause(ex, TimeoutException.class) || isStatus(ex, HttpStatus.GATEWAY_TIMEOUT)) {
            log.warn("Downstream timeout for {}: {}", path, rootMessage(ex));
            return ProblemResponses.write(exchange, objectMapper, HttpStatus.GATEWAY_TIMEOUT,
                    "Gateway timeout", "The downstream service did not respond in time.");
        }
        if (ex instanceof ResponseStatusException rse) {
            HttpStatusCode status = rse.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            return ProblemResponses.write(exchange, objectMapper, status,
                    known != null ? known.getReasonPhrase() : "Error",
                    status.value() == 404 ? "No route or resource for " + path : rse.getReason());
        }

        log.error("Unexpected gateway error for {}", path, ex);
        return ProblemResponses.write(exchange, objectMapper, HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal error", "An unexpected error occurred");
    }

    private static boolean isStatus(Throwable ex, HttpStatus status) {
        return ex instanceof ResponseStatusException rse && rse.getStatusCode().value() == status.value();
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
