package com.banking.gateway.web;

import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a correlation ID ({@code X-Request-Id}), forwards it to the service,
 * and returns it in the response. With one ID you can find the gateway log line AND the
 * service log lines for the same call.
 *
 * <p>Phase 14: also returns {@code X-Trace-Id}, the OpenTelemetry trace this request started. The
 * request ID finds log lines; the trace ID opens the whole call tree in Jaeger (gateway → services
 * → Kafka → consumers). The frontend shows both next to an error, so support can go straight to it.
 *
 * <p>It's a WebFilter that runs first (before security), so even 401/429 responses carry the ID.
 * A client-supplied ID is reused only if it looks safe, which blocks log injection.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String TRACE_HEADER = "X-Trace-Id";
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
            // By now WebFlux's server observation has started the trace (this filter runs too early to see it).
            traceId(withId.getAttributes()).ifPresent(id -> withId.getResponse().getHeaders().set(TRACE_HEADER, id));
            return Mono.empty();
        });
        return chain.filter(withId);
    }

    /** The trace ID of this request's server observation; empty when tracing is off. */
    static Optional<String> traceId(Map<String, Object> attributes) {
        return ServerRequestObservationContext.findCurrent(attributes)
                .map(context -> (TracingContext) context.get(TracingContext.class))
                .map(TracingContext::getSpan)
                .map(span -> span.context().traceId());
    }
}
