package com.restaurant.pos.costing.domain;

/**
 * How the running stock cost of a product is maintained (ADempiere-style costing methods).
 */
public enum CostingMethod {
    /** Weighted average recalculated on every inbound movement (purchase receipt). */
    AVERAGE_PO,
    /** Cost = net price of the most recent purchase receipt. */
    LAST_PO,
    /** Cost is maintained manually; receipts do not change it. */
    STANDARD;

    public static CostingMethod fromString(String value) {
        if (value == null || value.isBlank()) {
            return AVERAGE_PO;
        }
        try {
            return CostingMethod.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return AVERAGE_PO;
        }
    }
}
