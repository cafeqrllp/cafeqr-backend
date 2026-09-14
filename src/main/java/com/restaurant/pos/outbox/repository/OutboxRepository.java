package com.restaurant.pos.outbox.repository;

import com.restaurant.pos.outbox.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for the transactional outbox.
 * <p>
 * The {@code claimPendingEvents} query uses PostgreSQL's {@code FOR UPDATE SKIP LOCKED}
 * to allow multiple application instances to concurrently process outbox events without
 * grabbing the same rows.
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims a bounded batch of pending outbox events for processing.
     * Uses {@code FOR UPDATE SKIP LOCKED} to prevent contention across
     * multiple worker instances.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
              AND available_at <= :now
            ORDER BY created_at ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<OutboxEvent> claimPendingEvents(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    /**
     * Deletes completed outbox events older than the given cutoff timestamp.
     * Used by the cleanup/archival process.
     */
    @Modifying
    @Query(value = """
            DELETE FROM outbox_events
            WHERE status = 'COMPLETED'
              AND processed_at < :cutoff
            """, nativeQuery = true)
    int deleteCompletedBefore(@Param("cutoff") Instant cutoff);

    /**
     * Recovers events stuck in PROCESSING status back to PENDING.
     * Handles cases where worker node crashed mid-processing.
     */
    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET status = 'PENDING',
                available_at = CURRENT_TIMESTAMP
            WHERE status = 'PROCESSING'
              AND available_at <= :cutoff
            """, nativeQuery = true)
    int recoverStaleProcessing(@Param("cutoff") Instant cutoff);
}
