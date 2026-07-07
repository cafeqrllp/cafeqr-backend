package com.restaurant.pos.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculationRequest {
    private List<CalculationLineRequest> lines;
    private String orderDiscountType; // "PERCENT", "AMOUNT", or null
    private BigDecimal orderDiscountValue;
    private BigDecimal requestedRoundOff;
    private String roundOffMode;       // "AUTOMATIC", "MANUAL", or "DISABLED" — overrides config
    private UUID orgId;
}
