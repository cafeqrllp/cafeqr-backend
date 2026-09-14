package com.restaurant.pos.outbox.repository;

import com.restaurant.pos.outbox.domain.ProcessedEvent;
import com.restaurant.pos.outbox.domain.ProcessedEventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for consumer-side event deduplication.
 * <p>
 * Before processing an outbox event, consumers check
 * {@code existsByConsumerNameAndEventId} to avoid duplicate execution.
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {

    boolean existsByConsumerNameAndEventId(String consumerName, UUID eventId);
}
