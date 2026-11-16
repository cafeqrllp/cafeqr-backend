package com.restaurant.pos.costing.domain;

import java.math.BigDecimal;

/**
 * Cost figures to stamp on a stock ledger row.
 *
 * @param unitCost   cost per unit applied to this movement
 * @param totalCost  unitCost x quantity change (signed like the quantity)
 * @param costBefore product cost before the movement
 * @param costAfter  product cost after the movement
 */
public record CostResult(BigDecimal unitCost, BigDecimal totalCost, BigDecimal costBefore, BigDecimal costAfter) {
}
