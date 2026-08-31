package com.restaurant.pos.loyalty.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Defines the points-to-discount conversion for a {@link LoyaltyProgram}.
 * V1: {@code pointsRequired} points = {@code discountAmount} ₹ discount.
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "loyalty_redemption_rule")
public class LoyaltyRedemptionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "program_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private LoyaltyProgram program;

    /** Points required per redemption slab (e.g. 100). */
    @Column(name = "points_required", nullable = false)
    private int pointsRequired;

    /** Discount value granted per slab in currency units (e.g. ₹10). */
    @Column(name = "discount_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal discountAmount;

    /** Minimum points the customer must have to redeem anything. */
    @Builder.Default
    @Column(name = "min_points", nullable = false)
    private int minPoints = 0;

    /** Maximum points that can be redeemed in a single order. NULL = unlimited. */
    @Column(name = "max_points_per_order")
    private Integer maxPointsPerOrder;

    @Builder.Default
    @Column(name = "allow_partial", nullable = false)
    private boolean allowPartial = true;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
