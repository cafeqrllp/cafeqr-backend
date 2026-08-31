package com.restaurant.pos.loyalty.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks the loyalty balance for a single customer in a (client, org) context.
 *
 * <p>{@code currentPoints} is a fast-read denormalized balance that is always
 * kept in sync with the {@link LoyaltyTransaction} ledger within the same
 * transaction. Never trust or modify it in isolation — always update via the
 * {@code LoyaltyCommandHandler}.
 *
 * <p>The {@code version} field enables JPA optimistic locking; for redemptions
 * (where concurrent point deductions could create a negative balance) the
 * repository also offers a pessimistic-write variant.
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "customer_loyalty")
public class CustomerLoyalty {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "program_id")
    private UUID programId;

    @Builder.Default
    @Column(name = "current_points", nullable = false)
    private int currentPoints = 0;

    @Builder.Default
    @Column(name = "lifetime_earned", nullable = false)
    private int lifetimeEarned = 0;

    @Builder.Default
    @Column(name = "lifetime_redeemed", nullable = false)
    private int lifetimeRedeemed = 0;

    /** JPA optimistic-lock counter — incremented on every save. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── Domain helpers ────────────────────────────────────────────────────────

    public void creditPoints(int points) {
        this.currentPoints += points;
        this.lifetimeEarned += points;
        this.updatedAt = Instant.now();
    }

    public void debitPoints(int points) {
        if (points > this.currentPoints) {
            throw new IllegalStateException(
                    "Insufficient loyalty points. Available: " + currentPoints + ", requested: " + points);
        }
        this.currentPoints -= points;
        this.lifetimeRedeemed += points;
        this.updatedAt = Instant.now();
    }
}
