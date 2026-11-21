package com.banking.accountservice.events;

import com.banking.accountservice.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Announces balance changes.
 *
 * <p>Phase 11 sent to Kafka AFTER the commit, which left a gap: a crash or a Kafka outage between
 * the commit and the send lost the event ("dual write"). Phase 13 writes the event into the
 * outbox table INSIDE the balance-changing transaction instead. {@code OutboxRelay} delivers it
 * once Kafka is reachable. No more "EVENT LOST", and a rollback still discards the event.
 */
@Component
public class AccountEventPublisher {

    private final OutboxWriter outbox;
    private final String topic;

    public AccountEventPublisher(OutboxWriter outbox,
                                 @Value("${banking.kafka.topics.account-transactions}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    /** Key = accountId: all events of one account go to one partition, in order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(AccountTransactionEvent event) {
        outbox.append(topic, event.accountId().toString(), event.eventId(), event);
    }
}
