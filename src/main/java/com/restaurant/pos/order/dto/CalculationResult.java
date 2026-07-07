package com.restaurant.pos.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculationResult {
    private List<CalculatedLine> lines;

    private BigDecimal grossAmount; // pre-discount face total

    private BigDecimal lineDiscountDisplayAmount; // total line discounts (in display unit)
    private BigDecimal orderDiscountDisplayAmount; // total order discount (in display unit)

    private BigDecimal totalLineDiscountBase; // total line discounts ex-tax
    private BigDecimal totalOrderDiscountBase; // total order discounts ex-tax

    private BigDecimal taxableAmount;
    private BigDecimal totalTax;

    private BigDecimal totalBeforeRoundOff;
    private BigDecimal roundOffAmount;

    private BigDecimal grandTotal; // final settled amount

    @Builder.Default
    private String engineVersion = "GST_ENGINE_V1";
}
