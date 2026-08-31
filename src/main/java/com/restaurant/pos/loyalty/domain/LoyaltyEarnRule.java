package com.restaurant.pos.loyalty.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Defines how points are earned in a {@link LoyaltyProgram}.
 * V1: every {@code spendAmount} ₹ spent → {@code earnPoints} points (integer floor).
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "loyalty_earn_rule")
public class LoyaltyEarnRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "program_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private LoyaltyProgram program;

    /** Spend threshold in currency units (e.g. 100 = ₹100). */
    @Column(name = "spend_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal spendAmount;

    /** Points awarded for each full {@code spendAmount} block. */
    @Column(name = "earn_points", nullable = false)
    private int earnPoints;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
