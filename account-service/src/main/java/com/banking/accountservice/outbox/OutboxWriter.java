package com.banking.accountservice.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Queues an event for Kafka by inserting it into {@code outbox_events}.
 *
 * <p>{@code MANDATORY}: there must already be a transaction (the one changing the balance).
 * Called without one, this fails loudly instead of quietly committing the event on its own,
 * which would bring back the very gap the outbox exists to close.
 */
@Component
public class OutboxWriter {

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    /** Tracer/Propagator are optional: tests run with tracing switched off (no-op then). */
    public OutboxWriter(EntityManager entityManager, ObjectMapper objectMapper,
                        ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String key, UUID eventId, Object event) {
        // persist, not save(): the ID is assigned by us, so save() would first SELECT to check if it exists.
        entityManager.persist(new OutboxEvent(eventId, topic, key, toJson(event), Instant.now(), currentTraceParent()));
    }

    /** Phase 14: remember which trace caused this event, so the relay can continue it. */
    private String currentTraceParent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get("traceparent");
    }

    private String toJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize " + event, ex);
        }
    }
}
