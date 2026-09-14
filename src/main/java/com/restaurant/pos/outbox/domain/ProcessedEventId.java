package com.restaurant.pos.outbox.domain;

import lombok.*;

import java.io.Serializable;
import java.util.UUID;

/**
 * Composite primary key for {@link ProcessedEvent}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ProcessedEventId implements Serializable {
    private String consumerName;
    private UUID eventId;
}
