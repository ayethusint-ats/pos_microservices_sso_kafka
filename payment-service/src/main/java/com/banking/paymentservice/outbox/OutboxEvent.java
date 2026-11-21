package com.banking.paymentservice.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One Kafka message waiting to be sent (or already sent). Written in the SAME database transaction
 * as the business change, so "the state changed" and "there is an event to announce it" are
 * committed together or not at all. That's the whole Transactional Outbox pattern.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    private static final int MAX_ERROR_LENGTH = 500;

    /** The event's own eventId: consumers deduplicate on it, so a re-sent row is harmless. */
    @Id
    private UUID id;

    /** Assigned by the database (identity column): the order in which the relay publishes. */
    @Column(insertable = false, updatable = false)
    private Long seq;

    @Column(nullable = false, updatable = false, length = 100)
    private String topic;

    @Column(name = "message_key", nullable = false, updatable = false, length = 100)
    private String messageKey;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    /** Phase 14: W3C traceparent of the writing transaction; null when it ran outside a trace. */
    @Column(updatable = false, length = 55)
    private String traceParent;

    protected OutboxEvent() {
        // for JPA
    }

    public OutboxEvent(UUID id, String topic, String messageKey, String payload, Instant createdAt) {
        this(id, topic, messageKey, payload, createdAt, null);
    }

    public OutboxEvent(UUID id, String topic, String messageKey, String payload, Instant createdAt, String traceParent) {
        this.traceParent = traceParent;
        this.id = Objects.requireNonNull(id);
        this.topic = Objects.requireNonNull(topic);
        this.messageKey = Objects.requireNonNull(messageKey);
        this.payload = Objects.requireNonNull(payload);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    void markPublished(Instant at) {
        attempts++;
        publishedAt = at;
        lastError = null;
    }

    void markFailed(String error) {
        attempts++;
        lastError = error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public Long getSeq() {
        return seq;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public String getTraceParent() {
        return traceParent;
    }
}
