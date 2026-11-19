package com.banking.gateway.security;

import com.banking.gateway.web.ProblemResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reactive versions of the services' 401 / 403 / 503 handlers (Phase 5), with the same
 * semantics and the same problem+json body.
 *
 * <p>Spring's reactive Bearer entry point completes the response itself, so we can't append a
 * body to it. That's why the RFC 6750 {@code WWW-Authenticate} header is built here.
 */
public final class ReactiveSecurityErrorHandlers {

    private static final Logger log = LoggerFactory.getLogger(ReactiveSecurityErrorHandlers.class);

    private ReactiveSecurityErrorHandlers() {
    }

    /** 401: missing or invalid token. */
    public static ServerAuthenticationEntryPoint entryPoint(ObjectMapper objectMapper) {
        return (exchange, ex) -> unauthorized(exchange, ex, objectMapper);
    }

    /** 403: valid token, insufficient permission. */
    public static ServerAccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (exchange, ex) -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                    "Bearer error=\"insufficient_scope\", error_description=\"The request requires higher privileges than provided by the access token.\"");
            return ProblemResponses.write(exchange, objectMapper, HttpStatus.FORBIDDEN,
                    "Forbidden", "You do not have permission to perform this operation");
        };
    }

    /** Bad token → 401. Identity provider unreachable (no cached keys) → 503 + Retry-After. */
    public static ServerAuthenticationFailureHandler failureHandler(ObjectMapper objectMapper) {
        return (webFilterExchange, ex) -> {
            ServerWebExchange exchange = webFilterExchange.getExchange();
            if (ex instanceof AuthenticationServiceException) {
                log.warn("Cannot validate access tokens, identity provider unavailable: {}", ex.getMessage());
                exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "10");
                return ProblemResponses.write(exchange, objectMapper, HttpStatus.SERVICE_UNAVAILABLE,
                        "Service unavailable", "Token validation is temporarily unavailable. Please retry shortly.");
            }
            return unauthorized(exchange, ex, objectMapper);
        };
    }

    private static Mono<Void> unauthorized(ServerWebExchange exchange, AuthenticationException ex,
                                           ObjectMapper objectMapper) {
        boolean invalidToken = ex instanceof InvalidBearerTokenException;
        String header = "Bearer";
        if (invalidToken && ((InvalidBearerTokenException) ex).getError() instanceof BearerTokenError error) {
            header = "Bearer error=\"%s\", error_description=\"%s\"".formatted(
                    error.getErrorCode(), sanitize(error.getDescription()));
        }
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, header);
        return ProblemResponses.write(exchange, objectMapper, HttpStatus.UNAUTHORIZED, "Unauthorized",
                invalidToken ? "The access token is invalid or expired" : "An access token is required");
    }

    /** Header values must not contain quotes or line breaks. */
    private static String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[\"\\r\\n]", "'");
    }
}
