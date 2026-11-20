package com.banking.notificationservice.messaging;

import com.banking.notificationservice.events.CustomerEvent;
import com.banking.notificationservice.notification.CustomerDirectory;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Rebuilds the {@link CustomerDirectory} from the compacted topic.
 *
 * <p>Unlike a normal listener, it ignores committed offsets and seeks to the BEGINNING every time
 * partitions are assigned: the directory lives in memory, so after a restart it must re-read every
 * customer. Compaction keeps that cheap: Kafka retains roughly one record per customer, not every edit.
 */
@Component
public class CustomerDirectoryListener implements ConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(CustomerDirectoryListener.class);

    private final CustomerDirectory directory;

    public CustomerDirectoryListener(CustomerDirectory directory) {
        this.directory = directory;
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        callback.seekToBeginning(assignments.keySet());
        log.info("Rebuilding customer directory from the beginning of {}", assignments.keySet());
    }

    @KafkaListener(topics = "${banking.kafka.topics.customer-events}", groupId = "notification-service-directory")
    public void on(@Payload(required = false) CustomerEvent customer,
                   @Header(KafkaHeaders.RECEIVED_KEY) String keycloakUserId) {
        if (customer == null) {
            directory.remove(keycloakUserId);   // tombstone: profile deleted
        } else {
            directory.upsert(customer);
        }
    }
}
