package com.restaurant.pos.order.dto;

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
public class CalculationLineRequest {
    private UUID lineId;
    private UUID clientLineId;
    private UUID productId;
    private UUID variantId;
    private String productName;
    private String categoryName;
    private Boolean isPackagedGood;

    private BigDecimal quantity;
    private BigDecimal unitPrice; // face/MRP
    private BigDecimal taxRate;
    private String taxType; // "INCLUSIVE", "EXCLUSIVE", or "NONE"
    
    private String taxCode;
    private String taxName;

    // Line-level discount
    private String lineDiscountType; // "PERCENT" or "AMOUNT" or null
    private BigDecimal lineDiscountValue;

    // Backward compatibility helpers
    private BigDecimal discountAmount;
    private BigDecimal discountPercent;
}
