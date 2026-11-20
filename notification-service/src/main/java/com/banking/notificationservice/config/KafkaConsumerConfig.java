package com.banking.notificationservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.JsonMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.util.backoff.FixedBackOff;

/**
 * How messages become objects, and what happens when processing fails.
 * Spring Boot plugs both beans into the auto-configured listener container factory.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /** Matches infrastructure/kafka/create-topics.sh. */
    public static final String DLT_SUFFIX = ".DLT";

    /**
     * Values arrive as plain Strings (StringDeserializer); this converter turns the JSON into the
     * listener parameter's type with the application's ObjectMapper (ISO dates, unknown fields ignored).
     * No Java class names in message headers: producer and consumer share only the JSON contract.
     */
    @Bean
    RecordMessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new JsonMessageConverter(objectMapper);
    }

    /**
     * Failure policy:
     * <ul>
     *   <li>Transient errors (database briefly down...): retry twice, 1 s apart, on the same consumer.</li>
     *   <li>Then, or immediately for errors retrying can't fix (not JSON, invalid event): publish the
     *       ORIGINAL record to {@code <topic>.DLT} with the exception in its headers, and move on.</li>
     * </ul>
     * Without this, one "poison" message would block its partition forever (retry, fail, retry...).
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaTemplate) {
        // Destination named EXPLICITLY: <topic>.DLT, same partition. (The library default changed
        // across Spring Kafka versions: ".DLT" before, "-dlt" now. A topic that doesn't exist would
        // block this consumer while the producer waits for its metadata.) If publishing to the DLT
        // fails, the recoverer throws, the offset is NOT committed, and the record is retried.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            log.error("Sending record to DLT: topic={} partition={} offset={} key={} cause={}",
                    record.topic(), record.partition(), record.offset(), record.key(), ex.getMessage());
            recoverer.accept(record, ex);
        }, new FixedBackOff(1000L, 2));
        return handler;
    }
}
