package com.restaurant.pos.order.service;

import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.order.dto.CalculationLineRequest;
import com.restaurant.pos.order.dto.CalculationRequest;
import com.restaurant.pos.order.dto.CalculationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Totals follow the default currency's decimal places (0 for JPY-like, 3 for KWD-like). */
class CurrencyScaleRoundingTest {

    private OrderCalculationService calculationService;

    @BeforeEach
    void setUp() {
        calculationService = new OrderCalculationService();
    }

    private CalculationResult calculate(int decimalPlaces, String unitPrice, String qty,
                                        String orderDiscountType, String orderDiscountValue) {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(false)
                .pricesIncludeTax(false)
                .roundOffMode("DISABLED")
                .currencyDecimalPlaces(decimalPlaces)
                .build();
        CalculationLineRequest line = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(new BigDecimal(qty))
                .unitPrice(new BigDecimal(unitPrice))
                .taxRate(BigDecimal.ZERO)
                .taxType("EXCLUSIVE")
                .lineDiscountType("AMOUNT")
                .lineDiscountValue(BigDecimal.ZERO)
                .build();
        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line))
                .orderDiscountType(orderDiscountType)
                .orderDiscountValue(new BigDecimal(orderDiscountValue))
                .build();
        return calculationService.calculate(request, config);
    }

    @Test
    void zeroDecimalCurrencyRoundsTotalsToWholeUnits() {
        // 111 x 3 = 333; 10% off = 33.3 -> 33 at 0 places; payable 300
        CalculationResult result = calculate(0, "111", "3", "PERCENT", "10");

        assertThat(result.getGrandTotal().scale()).isZero();
        assertThat(result.getGrandTotal()).isEqualByComparingTo("300");
    }

    @Test
    void threeDecimalCurrencyKeepsThreeDecimals() {
        // 1.250 x 3 = 3.750 payable with no discount
        CalculationResult result = calculate(3, "1.250", "3", "AMOUNT", "0");

        assertThat(result.getGrandTotal().scale()).isEqualTo(3);
        assertThat(result.getGrandTotal()).isEqualByComparingTo("3.750");
    }
}
