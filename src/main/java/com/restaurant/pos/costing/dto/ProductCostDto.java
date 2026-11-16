package com.restaurant.pos.costing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCostDto {
    private UUID productId;
    private UUID variantId;
    private UUID orgId;
    private String costingMethod;
    private BigDecimal currentCost;
    private BigDecimal currentQty;
    private BigDecimal lastPurchasePrice;
    private LocalDateTime updatedAt;
}
