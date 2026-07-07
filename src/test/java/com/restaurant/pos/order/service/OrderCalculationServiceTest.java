package com.restaurant.pos.order.service;

import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.order.dto.CalculatedLine;
import com.restaurant.pos.order.dto.CalculationLineRequest;
import com.restaurant.pos.order.dto.CalculationRequest;
import com.restaurant.pos.order.dto.CalculationResult;
import com.restaurant.pos.order.domain.TaxType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderCalculationServiceTest {

    private OrderCalculationService calculationService;

    @BeforeEach
    void setUp() {
        calculationService = new OrderCalculationService();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-01  Pure NONE (GST off), no discount
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc01_pureExclusiveNoDiscountNoGst() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(false)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .build();

        CalculationLineRequest line = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(new BigDecimal("2.00"))
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(new BigDecimal("5.00"))
                .taxType("EXCLUSIVE")
                .lineDiscountType("AMOUNT")
                .lineDiscountValue(BigDecimal.ZERO)
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line))
                .orderDiscountType("AMOUNT")
                .orderDiscountValue(BigDecimal.ZERO)
                .build();

        CalculationResult result = calculationService.calculate(request, config);

        // GST disabled → taxType = NONE, rate=0, gross face = base × 1 = 200
        assertThat(result.getGrossAmount()).isEqualByComparingTo("200.00");
        assertThat(result.getTaxableAmount()).isEqualByComparingTo("200.00");
        assertThat(result.getTotalTax()).isEqualByComparingTo("0.00");
        assertThat(result.getGrandTotal()).isEqualByComparingTo("200.00");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-02  EXCLUSIVE, GST 18%, no discount
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc02_pureExclusiveGstNoDiscount() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(true)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .build();

        CalculationLineRequest line = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(new BigDecimal("2.00"))
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(new BigDecimal("18.00"))
                .taxType("EXCLUSIVE")
                .lineDiscountType("AMOUNT")
                .lineDiscountValue(BigDecimal.ZERO)
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line))
                .orderDiscountType("AMOUNT")
                .orderDiscountValue(BigDecimal.ZERO)
                .build();

        CalculationResult result = calculationService.calculate(request, config);

        // grossFaceAmount = base × (1 + 18%) = 200 × 1.18 = 236
        assertThat(result.getGrossAmount()).isEqualByComparingTo("236.00");
        assertThat(result.getTaxableAmount()).isEqualByComparingTo("200.00");
        assertThat(result.getTotalTax()).isEqualByComparingTo("36.00");
        assertThat(result.getGrandTotal()).isEqualByComparingTo("236.00");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-03  EXCLUSIVE, amount order discount (face pool proportional)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc03_exclusiveAmountOrderDiscount() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(true)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .build();

        CalculationLineRequest line = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(new BigDecimal("2.00"))
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(new BigDecimal("18.00"))
                .taxType("EXCLUSIVE")
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line))
                .orderDiscountType("AMOUNT")
                .orderDiscountValue(new BigDecimal("20.00"))
                .build();

        CalculationResult result = calculationService.calculate(request, config);

        // Face pool = 200 × 1.18 = 236.  Discount ₹20 (face).
        // Ex-tax discount = 20 / 1.18 = 16.95 (HALF_UP dp=8).
        // Taxable = 200 - 16.95 = 183.05.
        // Tax = 183.05 × 18% = 32.95.  Total = 216.00.
        assertThat(result.getGrossAmount()).isEqualByComparingTo("236.00");
        assertThat(result.getTaxableAmount()).isEqualByComparingTo("183.05");
        assertThat(result.getTotalTax()).isEqualByComparingTo("32.95");
        assertThat(result.getGrandTotal()).isEqualByComparingTo("216.00");
        assertThat(result.getOrderDiscountDisplayAmount()).isEqualByComparingTo("20.00");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-04  EXCLUSIVE, percent order discount (direct per-line face percentage)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc04_exclusivePercentOrderDiscount() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(true)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .build();

        CalculationLineRequest line = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(new BigDecimal("2.00"))
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(new BigDecimal("18.00"))
                .taxType("EXCLUSIVE")
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line))
                .orderDiscountType("PERCENT")
                .orderDiscountValue(new BigDecimal("10.00"))
                .build();

        CalculationResult result = calculationService.calculate(request, config);

        // 10% of face 236 = 23.6.  Ex-tax = 23.6 / 1.18 = 20.
        // Taxable = 200 - 20 = 180.  Tax = 180 × 18% = 32.40.  Total = 212.40.
        assertThat(result.getGrossAmount()).isEqualByComparingTo("236.00");
        assertThat(result.getTaxableAmount()).isEqualByComparingTo("180.00");
        assertThat(result.getTotalTax()).isEqualByComparingTo("32.40");
        assertThat(result.getGrandTotal()).isEqualByComparingTo("212.40");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-05  Mixed EXCLUSIVE + INCLUSIVE, amount order discount
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc05_mixedInclusiveExclusiveAmountOrderDiscount() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(true)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .build();

        CalculationLineRequest line1 = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(new BigDecimal("18.00"))
                .taxType("EXCLUSIVE")
                .build();

        CalculationLineRequest line2 = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("118.00"))
                .taxRate(new BigDecimal("18.00"))
                .taxType("INCLUSIVE")
                .isPackagedGood(true)
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line1, line2))
                .orderDiscountType("AMOUNT")
                .orderDiscountValue(new BigDecimal("20.00"))
                .build();

        CalculationResult result = calculationService.calculate(request, config);

        // Face pool = 118 (excl) + 118 (incl) = 236. Discount ₹20.
        // Proportional: each line share = 10.00 face.
        //   Line 1 (EXCLUSIVE): discBase = 10/1.18 = 8.47. taxable = 91.53.
        //   tax = 91.53 × 18% = 16.48. total = 108.01.
        //   Line 2 (INCLUSIVE): faceTotal = 118 - 10 = 108.00. taxable = 91.53. tax = 16.47.
        // Grand total = 108.01 + 108.00 = 216.01.
        assertThat(result.getGrandTotal()).isEqualByComparingTo("216.01");
        assertThat(result.getLines().get(0).getAllocatedOrderDiscountFace()).isEqualByComparingTo("10.00");
        assertThat(result.getLines().get(1).getAllocatedOrderDiscountFace()).isEqualByComparingTo("10.00");
        assertThat(result.getLines().get(0).getAllocatedOrderDiscountBase()).isEqualByComparingTo("8.47");
        assertThat(result.getLines().get(1).getAllocatedOrderDiscountBase()).isEqualByComparingTo("8.47");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-06  Mixed, percent order discount
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc06_mixedInclusiveExclusivePercentOrderDiscount() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(true)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .build();

        CalculationLineRequest line1 = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(new BigDecimal("18.00"))
                .taxType("EXCLUSIVE")
                .build();

        CalculationLineRequest line2 = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("118.00"))
                .taxRate(new BigDecimal("18.00"))
                .taxType("INCLUSIVE")
                .isPackagedGood(true)
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line1, line2))
                .orderDiscountType("PERCENT")
                .orderDiscountValue(new BigDecimal("10.00"))
                .build();

        CalculationResult result = calculationService.calculate(request, config);

        // 10% per-line face percentage:
        //   Line 1 (EXCLUSIVE): discFace = 118 × 10% = 11.80. discBase = 11.80/1.18 = 10.00.
        //   taxable = 90.00. tax = 16.20. total = 106.20.
        //   Line 2 (INCLUSIVE): discFace = 118 × 10% = 11.80. faceTotal = 106.20.
        //   taxable = 90.00. tax = 16.20. total = 106.20.
        assertThat(result.getLines().get(0).getLineTotal()).isEqualByComparingTo("106.20");
        assertThat(result.getLines().get(1).getLineTotal()).isEqualByComparingTo("106.20");
        assertThat(result.getGrandTotal()).isEqualByComparingTo("212.40");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-07  Amount discount exceeding eligible total → BusinessException
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc07_amountDiscountExceedingTotalThrows() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(true)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .build();

        CalculationLineRequest line = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(new BigDecimal("18.00"))
                .taxType("EXCLUSIVE")
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line))
                .orderDiscountType("AMOUNT")
                .orderDiscountValue(new BigDecimal("200.00")) // face pool = 118; 200 > 118
                .build();

        assertThatThrownBy(() -> calculationService.calculate(request, config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot exceed");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-08  Line discount + order discount combined
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc08_lineDiscountAndOrderDiscountCombined() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(true)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .build();

        CalculationLineRequest line = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(new BigDecimal("18.00"))
                .taxType("EXCLUSIVE")
                .lineDiscountType("AMOUNT")
                .lineDiscountValue(new BigDecimal("10.00")) // ex-tax face discount ₹10
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line))
                .orderDiscountType("AMOUNT")
                .orderDiscountValue(new BigDecimal("9.00")) // face ₹9
                .build();

        CalculationResult result = calculationService.calculate(request, config);

        // After line discount: baseAfter = 91.53. faceAfter = 108.00.
        // Order discount ₹9 (face).  Ex-tax = 9 / 1.18 = 7.63.
        // Final taxable = 91.53 - 7.63 = 83.90. tax = 83.90 × 18% = 15.10. total = 99.00.
        assertThat(result.getGrandTotal()).isEqualByComparingTo("99.00");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-09  Automatic round-off
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc09_automaticRoundOff() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(true)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .roundOffEnabled(true)
                .roundOffMode("automatic")
                .roundOffAutoFactor(BigDecimal.ONE)
                .build();

        CalculationLineRequest line = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.50"))
                .taxRate(BigDecimal.ZERO)
                .taxType("EXCLUSIVE")
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line))
                .build();

        CalculationResult result = calculationService.calculate(request, config);

        assertThat(result.getTotalBeforeRoundOff()).isEqualByComparingTo("100.50");
        assertThat(result.getRoundOffAmount()).isEqualByComparingTo("0.50");
        assertThat(result.getGrandTotal()).isEqualByComparingTo("101.00");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-10  Manual round-off
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc10_manualRoundOff() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(true)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .roundOffEnabled(true)
                .roundOffMode("manual")
                .build();

        CalculationLineRequest line = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.50"))
                .taxRate(BigDecimal.ZERO)
                .taxType("EXCLUSIVE")
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line))
                .requestedRoundOff(new BigDecimal("-0.50"))
                .build();

        CalculationResult result = calculationService.calculate(request, config);

        assertThat(result.getTotalBeforeRoundOff()).isEqualByComparingTo("100.50");
        assertThat(result.getRoundOffAmount()).isEqualByComparingTo("-0.50");
        assertThat(result.getGrandTotal()).isEqualByComparingTo("100.00");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-11  Explicit taxType=NONE overrides config
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc11_explicitTaxTypeNoneOverridesConfig() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(true)
                .pricesIncludeTax(false) // would normally be EXCLUSIVE
                .currencyDecimalPlaces(2)
                .build();

        CalculationLineRequest line = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(new BigDecimal("18.00"))
                .taxType("NONE") // explicit override
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line))
                .build();

        CalculationResult result = calculationService.calculate(request, config);

        assertThat(result.getLines().get(0).getTaxType()).isEqualTo(TaxType.NONE);
        assertThat(result.getTotalTax()).isEqualByComparingTo("0.00");
        assertThat(result.getGrandTotal()).isEqualByComparingTo("100.00");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-12  Invalid taxType string → BusinessException
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc12_invalidTaxTypeThrows() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(true)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .build();

        CalculationLineRequest line = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(new BigDecimal("18.00"))
                .taxType("BOGUS")
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line))
                .build();

        assertThatThrownBy(() -> calculationService.calculate(request, config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid tax type");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-13  Zero-value line is not eligible for order discount
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc13_zeroValueLineNotEligibleForOrderDiscount() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(true)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .build();

        CalculationLineRequest paidLine = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("20.00"))
                .taxRate(new BigDecimal("18.00"))
                .taxType("EXCLUSIVE")
                .build();

        // Complimentary / zero-price line
        CalculationLineRequest freeLine = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(BigDecimal.ZERO)
                .taxRate(new BigDecimal("18.00"))
                .taxType("EXCLUSIVE")
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(paidLine, freeLine))
                .orderDiscountType("AMOUNT")
                .orderDiscountValue(new BigDecimal("23.60")) // = 100% of face pool of paid line
                .build();

        CalculationResult result = calculationService.calculate(request, config);

        // Only paidLine is eligible (face > 0). Entire discount goes to it.
        assertThat(result.getLines().get(0).getAllocatedOrderDiscountFace()).isEqualByComparingTo("23.60");
        assertThat(result.getLines().get(1).getAllocatedOrderDiscountFace()).isEqualByComparingTo("0.00");
        assertThat(result.getGrandTotal()).isEqualByComparingTo("0.00");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-14  Manual round-off ≥ 1.00 → BusinessException
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc14_manualRoundOffExceedsLimitThrows() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(false)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .roundOffEnabled(true)
                .roundOffMode("manual")
                .build();

        CalculationLineRequest line = CalculationLineRequest.builder()
                .productId(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(BigDecimal.ZERO)
                .taxType("NONE")
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line))
                .requestedRoundOff(new BigDecimal("1.00")) // = limit, must be < 1
                .build();

        assertThatThrownBy(() -> calculationService.calculate(request, config))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("within");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TC-15  clientLineId is preserved through the pipeline
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void tc15_clientLineIdPreservedInResult() {
        ConfigurationDto config = ConfigurationDto.builder()
                .taxEnabled(false)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .build();

        UUID cid = UUID.randomUUID();
        CalculationLineRequest line = CalculationLineRequest.builder()
                .clientLineId(cid)
                .productId(UUID.randomUUID())
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(BigDecimal.ZERO)
                .taxType("NONE")
                .build();

        CalculationRequest request = CalculationRequest.builder()
                .lines(List.of(line))
                .build();

        CalculationResult result = calculationService.calculate(request, config);

        assertThat(result.getLines().get(0).getClientLineId()).isEqualTo(cid);
    }
}
