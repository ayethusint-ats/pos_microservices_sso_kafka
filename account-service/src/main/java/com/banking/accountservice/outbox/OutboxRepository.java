package com.banking.accountservice.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * The next unpublished events, oldest first, LOCKED for this transaction.
     * {@code SKIP LOCKED}: a second instance of the service skips rows the first one is sending
     * instead of waiting for them, so several relays can run without sending the same row twice.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY seq
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(int limit);

    long countByPublishedAtIsNull();

    /** Phase 14 metric: how long the oldest unsent event has been waiting (null = none waiting). */
    @Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.publishedAt IS NULL")
    Instant oldestUnpublishedCreatedAt();

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.publishedAt < :before")
    int deletePublishedBefore(Instant before);
}
