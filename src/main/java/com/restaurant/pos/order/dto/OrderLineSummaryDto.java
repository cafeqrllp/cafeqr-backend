package com.restaurant.pos.order.dto;

import com.restaurant.pos.order.domain.TaxType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderLineSummaryDto {
    private UUID id;
    private UUID productId;
    private UUID variantId;
    private String productName;
    private String categoryName;
    private Boolean isPackagedGood;
    private BigDecimal quantity;
    private String unitOfMeasure;
    private Integer uomPrecision;
    private BigDecimal unitPrice;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    private BigDecimal discountAmount;
    private BigDecimal lineTotal;

    // Tax Enrichment Fields (V1_110)
    private BigDecimal grossLineAmount;
    private BigDecimal unitPriceExTax;
    private BigDecimal taxableAmount;
    private TaxType taxType;
    private BigDecimal taxSnapshotRate;
    private String taxCode;
    private String taxName;
    private BigDecimal manualDiscountAmount;
    private BigDecimal manualDiscountPercent;
    private BigDecimal allocatedOrderDiscount;
}
