package com.banking.paymentservice.events;

import com.banking.paymentservice.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Announces payment outcomes through the outbox (Phase 13): the event row is inserted in the
 * transaction that saves the final status, so the status and its announcement can't disagree,
 * and a Kafka outage only delays the event instead of losing it.
 */
@Component
public class PaymentEventPublisher {

    private final OutboxWriter outbox;
    private final String topic;

    public PaymentEventPublisher(OutboxWriter outbox, @Value("${banking.kafka.topics.payment-events}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(PaymentEvent event) {
        outbox.append(topic, event.paymentId().toString(), event.eventId(), event);
    }
}
