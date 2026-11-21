package com.banking.paymentservice.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends committed outbox rows to Kafka, then marks them published.
 *
 * <p>Delivery is AT LEAST ONCE: if the process dies after Kafka acknowledged but before the row
 * was marked, the row is sent again on the next run. Consumers already deduplicate by eventId
 * (Phase 11), so the duplicate is harmless. What can no longer happen is an event that's LOST.
 *
 * <p>Order: rows are sent in {@code seq} order, and a batch stops at the first failure. A later
 * event for the same key never overtakes an earlier one that couldn't be sent yet.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final Tracer tracer;
    private final Propagator propagator;

    @Autowired
    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                       TransactionTemplate transactionTemplate,
                       @Value("${banking.outbox.batch-size:100}") int batchSize,
                       @Value("${banking.outbox.send-timeout:5s}") Duration sendTimeout,
                       @Value("${banking.outbox.retention:7d}") Duration retention,
                       ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this(repository, kafkaTemplate, transactionTemplate, batchSize, sendTimeout, retention,
                tracer.getIfAvailable(() -> Tracer.NOOP), propagator.getIfAvailable(() -> Propagator.NOOP));
    }

    OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                TransactionTemplate transactionTemplate, int batchSize, Duration sendTimeout, Duration retention,
                Tracer tracer, Propagator propagator) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** Runs every poll interval; drains the backlog batch by batch while Kafka keeps accepting. */
    @Scheduled(fixedDelayString = "${banking.outbox.poll-interval-ms:500}")
    public void publishPending() {
        BatchResult result;
        do {
            result = transactionTemplate.execute(status -> publishBatch());
        } while (result != null && result.complete() && result.sent() == batchSize);
    }

    /** Published rows are only history; keep them a while for debugging, then delete. */
    @Scheduled(cron = "${banking.outbox.cleanup-cron:0 17 * * * *}")
    public void deleteOldPublished() {
        Integer deleted = transactionTemplate.execute(status ->
                repository.deletePublishedBefore(Instant.now().minus(retention)));
        if (deleted != null && deleted > 0) {
            log.info("Deleted {} published outbox events older than {}", deleted, retention);
        }
    }

    /** One transaction: lock a batch, send it, wait for the acks, mark what was acknowledged. */
    BatchResult publishBatch() {
        List<OutboxEvent> batch = repository.lockNextBatch(batchSize);
        if (batch.isEmpty()) {
            return new BatchResult(0, true);
        }
        // Send all first (the producer pipelines them), then wait for each ack in order.
        List<CompletableFuture<SendResult<String, String>>> acks = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            try {
                acks.add(sendInOriginalTrace(event));
            } catch (RuntimeException ex) {
                // send() itself throws when the broker's metadata isn't available within max.block.ms.
                acks.add(CompletableFuture.failedFuture(ex));
                break;
            }
        }
        int sent = 0;
        for (int i = 0; i < acks.size(); i++) {
            OutboxEvent event = batch.get(i);
            try {
                acks.get(i).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                event.markPublished(Instant.now());
                sent++;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                event.markFailed("interrupted");
                return new BatchResult(sent, false);
            } catch (ExecutionException | TimeoutException ex) {
                Throwable cause = ex instanceof ExecutionException ? ex.getCause() : ex;
                String error = ex instanceof TimeoutException
                        ? "no acknowledgement from Kafka within " + sendTimeout.toMillis() + " ms"
                        : cause.getClass().getSimpleName() + ": " + cause.getMessage();
                event.markFailed(error);
                // Not an ERROR: nothing is lost, the row stays in the outbox and is retried.
                log.warn("Kafka unavailable, event {} and later ones wait in the outbox: {}", event.getId(), error);
                return new BatchResult(sent, false);
            }
        }
        if (sent > 0) {
            log.debug("Published {} outbox event(s)", sent);
        }
        return new BatchResult(sent, true);
    }

    /**
     * Phase 14: this runs in a scheduler thread, so there's no current trace. Restore the one stored
     * with the row: KafkaTemplate's observation then creates the producer span as its child and
     * writes {@code traceparent} into the Kafka headers, and the consumers continue the same trace.
     */
    private CompletableFuture<SendResult<String, String>> sendInOriginalTrace(OutboxEvent event) {
        if (event.getTraceParent() == null) {
            return kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload());
        }
        Span span = propagator.extract(Map.of("traceparent", event.getTraceParent()), Map::get)
                .name("outbox publish")
                .tag("outbox.event_id", event.getId().toString())
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload());
        } finally {
            span.end();
        }
    }

    record BatchResult(int sent, boolean complete) {
    }
}
