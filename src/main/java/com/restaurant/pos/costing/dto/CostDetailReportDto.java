package com.restaurant.pos.costing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Product cost report: every costed movement of a product in a period, with opening/closing cost and totals. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostDetailReportDto {
    private UUID productId;
    private UUID orgId;
    private String costingMethod;
    /** Cost before the first movement in the period (null when there is none). */
    private BigDecimal openingCost;
    /** Cost after the last movement in the period (null when there is none). */
    private BigDecimal closingCost;
    /** Current cost record of the product (base product), regardless of the period. */
    private BigDecimal currentCost;
    private BigDecimal qtyIn;
    private BigDecimal qtyOut;
    private BigDecimal valueIn;
    private BigDecimal valueOut;
    private List<CostDetailLineDto> lines;
}
