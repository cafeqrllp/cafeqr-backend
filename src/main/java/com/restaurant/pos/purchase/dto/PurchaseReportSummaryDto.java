package com.restaurant.pos.purchase.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Top-level executive summary DTO for Purchase Orders and Vendor Bills.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Executive summary of purchases within a specific date range")
public class PurchaseReportSummaryDto {

    @Schema(description = "Total purchase value (Grand Total of active purchase orders)")
    @Builder.Default
    private BigDecimal totalPurchases = BigDecimal.ZERO;

    @Schema(description = "Total amount paid to vendors for purchases")
    @Builder.Default
    private BigDecimal totalPaid = BigDecimal.ZERO;

    @Schema(description = "Outstanding vendor payables (unpaid/pending amounts)")
    @Builder.Default
    private BigDecimal totalUnpaid = BigDecimal.ZERO;

    @Schema(description = "Total tax paid on purchases")
    @Builder.Default
    private BigDecimal totalTax = BigDecimal.ZERO;

    @Schema(description = "Total discount received on purchases")
    @Builder.Default
    private BigDecimal totalDiscount = BigDecimal.ZERO;

    @Schema(description = "Total number of purchase orders")
    @Builder.Default
    private long totalOrders = 0;

    @Schema(description = "Number of received/completed purchase orders")
    @Builder.Default
    private long receivedOrders = 0;

    @Schema(description = "Number of draft purchase orders")
    @Builder.Default
    private long draftOrders = 0;

    @Schema(description = "Average purchase order value")
    @Builder.Default
    private BigDecimal averageOrderValue = BigDecimal.ZERO;

    @Schema(description = "Breakdown of order count by orderStatus")
    @Builder.Default
    private Map<String, Long> statusCounts = new HashMap<>();

    @Schema(description = "Breakdown of order count by paymentStatus")
    @Builder.Default
    private Map<String, Long> paymentStatusCounts = new HashMap<>();
}
