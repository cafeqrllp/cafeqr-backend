package com.restaurant.pos.loyalty.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable append-only audit ledger for loyalty point movements.
 *
 * <p><b>Rules:</b>
 * <ul>
 *   <li>Never update or delete rows.</li>
 *   <li>Order cancellations/refunds create a new {@code REVERSAL} row that
 *       references the original via {@code referenceTransactionId}.</li>
 *   <li>{@code points} is always positive for credits (EARN, REVERSAL of REDEEM)
 *       and always negative for debits (REDEEM, REVERSAL of EARN, EXPIRE).</li>
 * </ul>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "loyalty_transaction")
public class LoyaltyTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @Column(name = "customer_loyalty_id", nullable = false)
    private UUID customerLoyaltyId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "program_id")
    private UUID programId;

    @Column(name = "order_id")
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", length = 20, nullable = false)
    private LoyaltyTransactionType transactionType;

    /** Positive = credit, negative = debit. */
    @Column(nullable = false)
    private int points;

    /** Snapshot of the running balance immediately after this transaction. */
    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    /** For REVERSAL rows, points back to the transaction being reversed. */
    @Column(name = "reference_transaction_id")
    private UUID referenceTransactionId;

    @Column(columnDefinition = "TEXT")
    private String remarks;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
