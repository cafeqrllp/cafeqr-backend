package com.restaurant.pos.loyalty.domain;

/**
 * Type of loyalty ledger transaction.
 * The ledger is append-only — cancellations create a REVERSAL entry
 * instead of deleting or modifying existing rows.
 */
public enum LoyaltyTransactionType {
    /** Points credited when a qualifying order is completed. */
    EARN,
    /** Points debited when redeemed as a discount on an order. */
    REDEEM,
    /** Manual balance correction by an administrator. */
    ADJUSTMENT,
    /** Points expired per program policy (V2). */
    EXPIRE,
    /** Mirror entry that reverses a prior EARN or REDEEM on order cancellation/refund. */
    REVERSAL
}
