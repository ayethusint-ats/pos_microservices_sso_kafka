package com.banking.paymentservice.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Announces payment outcomes. Called after the final status was saved (each save commits on its
 * own, see PaymentService), so we never announce something the database doesn't have.
 *
 * <p>Fire-and-forget with logging: a Kafka outage must not fail a transfer whose money already
 * moved. The cost is a possible lost event ("dual write"), closed by the outbox in Phase 13.
 */
@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public PaymentEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                 @Value("${banking.kafka.topics.payment-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    public void publish(PaymentEvent event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize " + event, ex);
        }
        String key = event.paymentId().toString();
        try {
            kafkaTemplate.send(topic, key, json).whenComplete((result, failure) -> {
                if (failure != null) {
                    log.error("EVENT LOST (replay manually) topic={} key={} event={}", topic, key, json, failure);
                }
            });
        } catch (RuntimeException ex) {
            log.error("EVENT LOST (replay manually) topic={} key={} event={}", topic, key, json, ex);
        }
    }
}
