package com.restaurant.pos.outbox.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping the {@code processed_events} table.
 * Used by outbox consumers for idempotency — a consumer checks whether an
 * event has already been processed before executing side effects.
 */
@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEventId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEvent {

    @Id
    @Column(name = "consumer_name", nullable = false, length = 100)
    private String consumerName;

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    @Builder.Default
    private Instant processedAt = Instant.now();
}
