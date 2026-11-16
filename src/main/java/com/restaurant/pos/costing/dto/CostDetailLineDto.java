package com.restaurant.pos.costing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One costed stock movement of a product (the cost detail row, like ADempiere's M_CostDetail):
 * how the movement changed quantity and the product's current cost.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostDetailLineDto {
    private UUID ledgerId;
    private LocalDateTime transactionDate;
    private String transactionType;
    private UUID referenceId;
    /** Document number of the source document (PO / order / transfer / adjustment), when it can be resolved. */
    private String documentNo;
    private UUID warehouseId;
    private UUID variantId;
    private String variantName;
    private BigDecimal quantityChange;
    /** Warehouse stock balance after this movement. */
    private BigDecimal balanceAfter;
    private BigDecimal unitCost;
    private BigDecimal totalCost;
    private BigDecimal costBefore;
    private BigDecimal costAfter;
    /** False for movements written before inventory costing existed (no cost before/after). */
    private boolean costed;
}
