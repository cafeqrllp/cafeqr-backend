package com.restaurant.pos.purchase.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Item-wise purchase breakdown DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Aggregated item-wise procurement statistics")
public class PurchaseItemReportDto {

    @Schema(description = "Product / Item name")
    private String productName;

    @Schema(description = "Category name")
    private String categoryName;

    @Schema(description = "Unit of measure name / symbol")
    private String uom;

    @Schema(description = "Total quantity purchased")
    @Builder.Default
    private BigDecimal totalQuantity = BigDecimal.ZERO;

    @Schema(description = "Total spent on this product across orders")
    @Builder.Default
    private BigDecimal totalSpent = BigDecimal.ZERO;

    @Schema(description = "Average unit cost/purchase price")
    @Builder.Default
    private BigDecimal avgUnitCost = BigDecimal.ZERO;

    @Schema(description = "Decimal precision for unit of measure")
    private Integer uomPrecision;

    @Schema(description = "Number of distinct orders containing this item")
    @Builder.Default
    private long orderCount = 0;
}
