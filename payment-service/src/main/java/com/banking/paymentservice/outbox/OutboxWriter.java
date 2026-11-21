package com.banking.paymentservice.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Queues an event for Kafka by inserting it into {@code outbox_events}.
 *
 * <p>{@code MANDATORY}: there must already be a transaction (the one changing the business data).
 * Called without one, this fails loudly instead of quietly committing the event on its own,
 * which would bring back the very gap the outbox exists to close.
 */
@Component
public class OutboxWriter {

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public OutboxWriter(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String key, UUID eventId, Object event) {
        // persist, not save(): the ID is assigned by us, so save() would first SELECT to check if it exists.
        entityManager.persist(new OutboxEvent(eventId, topic, key, toJson(event), Instant.now()));
    }

    private String toJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize " + event, ex);
        }
    }
}
