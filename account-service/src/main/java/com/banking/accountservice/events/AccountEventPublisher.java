package com.banking.accountservice.events;

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
 * Sends account events to Kafka, but only AFTER the database transaction committed.
 *
 * <p>Why after commit: sending inside the transaction could announce a deposit that then rolls
 * back (optimistic-lock conflict, constraint violation). Consumers would show money that never existed.
 *
 * <p>The remaining gap ("dual write"): if the process dies, or Kafka is down, between the commit and
 * the send, the balance changed but the event is lost. We log it at ERROR with the full event so it
 * can be replayed by hand. Phase 13 closes the gap with the Transactional Outbox pattern.
 */
@Component
public class AccountEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AccountEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public AccountEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                 @Value("${banking.kafka.topics.account-transactions}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(AccountTransactionEvent event) {
        String json = toJson(event);
        try {
            // Asynchronous: send() returns at once, the callback runs when the broker acknowledged
            // (acks=all) or delivery finally failed.
            kafkaTemplate.send(topic, event.accountId().toString(), json).whenComplete((result, failure) -> {
                if (failure != null) {
                    log.error("EVENT LOST (replay manually) topic={} key={} event={}", topic, event.accountId(), json, failure);
                } else {
                    log.debug("Published {} to {}-{}@{}", event.eventId(), topic,
                            result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                }
            });
        } catch (RuntimeException ex) {
            // send() itself throws when metadata isn't available within max.block.ms (broker down).
            log.error("EVENT LOST (replay manually) topic={} key={} event={}", topic, event.accountId(), json, ex);
        }
    }

    private String toJson(AccountTransactionEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize " + event, ex);
        }
    }
}
