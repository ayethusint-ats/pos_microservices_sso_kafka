package com.banking.gateway.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a correlation ID ({@code X-Request-Id}), forwards it to the service,
 * and returns it in the response. With one ID you can find the gateway log line AND the
 * service log lines for the same call. Phase 14 adds full distributed tracing (W3C traceparent).
 *
 * <p>It's a WebFilter that runs first (before security), so even 401/429 responses carry the ID.
 * A client-supplied ID is reused only if it looks safe, which blocks log injection.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();

        ServerWebExchange withId = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(HEADER, requestId)))
                .build();
        withId.getAttributes().put(ATTRIBUTE, requestId);
        withId.getResponse().beforeCommit(() -> {
            withId.getResponse().getHeaders().set(HEADER, requestId);
            return Mono.empty();
        });
        return chain.filter(withId);
    }
}
