package com.restaurant.pos.loyalty.command;

import java.math.BigDecimal;

/**
 * Outcome of a loyalty redemption.
 *
 * @param discount       the discount the redeemed points are worth (server-computed)
 * @param alreadyApplied true when this order's redemption was already recorded earlier
 *                       (idempotent replay); {@code discount} is then null because no new
 *                       points were debited in this call
 */
public record RedemptionResult(BigDecimal discount, boolean alreadyApplied) {

    public static RedemptionResult applied(BigDecimal discount) {
        return new RedemptionResult(discount, false);
    }

    public static RedemptionResult replay() {
        return new RedemptionResult(null, true);
    }
}
