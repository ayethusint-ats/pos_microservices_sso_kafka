package com.banking.userservice.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes profile changes after the transaction committed (see account-service's
 * AccountEventPublisher for why "after commit", and the remaining dual-write gap).
 *
 * <p>Deletion publishes a TOMBSTONE: the key with a null value. On a compacted topic that's the
 * signal "forget this key": consumers drop their copy, and Kafka eventually removes the key entirely,
 * which matters for privacy (GDPR "right to erasure").
 */
@Component
public class CustomerEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CustomerEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public CustomerEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                  @Value("${banking.kafka.topics.customer-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(CustomerChanged change) {
        String json = change.state() == null ? null : toJson(change.state());
        try {
            kafkaTemplate.send(topic, change.keycloakUserId(), json).whenComplete((result, failure) -> {
                if (failure != null) {
                    log.error("EVENT LOST (replay manually) topic={} key={} event={}", topic, change.keycloakUserId(), json, failure);
                }
            });
        } catch (RuntimeException ex) {
            log.error("EVENT LOST (replay manually) topic={} key={} event={}", topic, change.keycloakUserId(), json, ex);
        }
    }

    private String toJson(CustomerEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize " + event, ex);
        }
    }
}
