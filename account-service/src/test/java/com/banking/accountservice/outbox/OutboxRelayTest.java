package com.banking.accountservice.outbox;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the relay does when Kafka fails halfway through a batch. */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxRepository repository;

    @Mock
    private KafkaTemplate<String, String> kafka;

    private OutboxRelay relay;
    private final OutboxEvent first = event("a");
    private final OutboxEvent second = event("b");
    private final OutboxEvent third = event("c");

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(repository, kafka, mock(TransactionTemplate.class), 100,
                Duration.ofMillis(200), Duration.ofDays(7), Tracer.NOOP, Propagator.NOOP);
        when(repository.lockNextBatch(100)).thenReturn(List.of(first, second, third));
    }

    @Test
    void everythingAcknowledgedIsMarkedPublished() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(ack());

        OutboxRelay.BatchResult result = relay.publishBatch();

        assertThat(result).isEqualTo(new OutboxRelay.BatchResult(3, true));
        assertThat(List.of(first, second, third)).allMatch(e -> e.getPublishedAt() != null);
    }

    @Test
    void aFailedSendStopsTheBatchSoNothingOvertakesIt() {
        when(kafka.send(anyString(), eq("a"), anyString())).thenReturn(ack());
        when(kafka.send(anyString(), eq("b"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("broker down")));
        when(kafka.send(anyString(), eq("c"), anyString())).thenReturn(ack());

        OutboxRelay.BatchResult result = relay.publishBatch();

        assertThat(result).isEqualTo(new OutboxRelay.BatchResult(1, false));
        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNull();                 // stays in the outbox, retried next run
        assertThat(second.getAttempts()).isEqualTo(1);
        assertThat(second.getLastError()).contains("broker down");
        assertThat(third.getPublishedAt()).isNull();                  // even though Kafka may have it: re-sent, deduplicated
    }

    @Test
    void aSendThatThrowsImmediatelyIsTreatedTheSame() {
        // KafkaTemplate.send throws itself when no broker metadata arrives within max.block.ms.
        when(kafka.send(anyString(), eq("a"), anyString())).thenThrow(new RuntimeException("metadata timeout"));

        OutboxRelay.BatchResult result = relay.publishBatch();

        assertThat(result).isEqualTo(new OutboxRelay.BatchResult(0, false));
        assertThat(first.getLastError()).contains("metadata timeout");
        verify(kafka, never()).send(anyString(), eq("b"), anyString());
    }

    private static OutboxEvent event(String key) {
        return new OutboxEvent(UUID.randomUUID(), "topic", key, "{}", Instant.now());
    }

    private static CompletableFuture<SendResult<String, String>> ack() {
        return CompletableFuture.completedFuture(null);
    }
}
