package com.banking.accountservice.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Phase 14: is the outbox keeping up? Two gauges, read from the database at every scrape:
 * <pre>
 *   banking_outbox_backlog                 events not yet sent (normally 0, briefly a few)
 *   banking_outbox_oldest_age_seconds      how long the oldest unsent one has waited
 * </pre>
 * The age is the better alert: a backlog of 50 that's 1 s old is a burst; a backlog of 1 that's
 * 10 minutes old means Kafka (or a topic) is broken, and consumers are working on stale data.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(OutboxRepository repository, MeterRegistry registry) {
        Gauge.builder("banking.outbox.backlog", repository, OutboxRepository::countByPublishedAtIsNull)
                .description("Outbox events waiting to be published to Kafka")
                .register(registry);
        Gauge.builder("banking.outbox.oldest.age", repository, OutboxMetrics::oldestAgeSeconds)
                .baseUnit("seconds")
                .description("Age of the oldest unpublished outbox event (0 if none)")
                .register(registry);
    }

    private static double oldestAgeSeconds(OutboxRepository repository) {
        Instant oldest = repository.oldestUnpublishedCreatedAt();
        return oldest == null ? 0 : Duration.between(oldest, Instant.now()).toMillis() / 1000.0;
    }
}
