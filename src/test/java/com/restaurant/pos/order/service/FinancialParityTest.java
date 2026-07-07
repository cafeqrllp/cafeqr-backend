package com.restaurant.pos.order.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.order.dto.CalculatedLine;
import com.restaurant.pos.order.dto.CalculationLineRequest;
import com.restaurant.pos.order.dto.CalculationRequest;
import com.restaurant.pos.order.dto.CalculationResult;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class FinancialParityTest {

    private final OrderCalculationService calculationService = new OrderCalculationService();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @TestFactory
    Stream<DynamicTest> runSharedParityScenarios() throws IOException {
        File jsonFile = new File("../cafe-qr-frontend/utils/financial_parity_scenarios.json");
        if (!jsonFile.exists()) {
            // Fallback for different build environments or runner cwd
            jsonFile = new File("cafe-qr-frontend/utils/financial_parity_scenarios.json");
        }
        if (!jsonFile.exists()) {
            fail("Shared parity test scenarios JSON file not found at " + jsonFile.getAbsolutePath());
        }

        List<ScenarioDto> scenarios = mapper.readValue(jsonFile, new TypeReference<List<ScenarioDto>>() {});

        return scenarios.stream().map(tc -> DynamicTest.dynamicTest(
                tc.id + ": " + tc.description,
                () -> {
                    // Set up Configuration
                    ConfigurationDto config = ConfigurationDto.builder()
                            .taxEnabled(tc.profile.taxEnabled)
                            .pricesIncludeTax(tc.profile.pricesIncludeTax)
                            .currencyDecimalPlaces(tc.profile.currencyDecimalPlaces)
                            .roundOffEnabled(tc.profile.roundOffConfig != null && tc.profile.roundOffConfig.roundOffEnabled)
                            .roundOffMode(tc.profile.roundOffConfig != null ? tc.profile.roundOffConfig.roundOffMode : "AUTOMATIC")
                            .roundOffAutoFactor(tc.profile.roundOffConfig != null ? tc.profile.roundOffConfig.roundOffAutoFactor : BigDecimal.ONE)
                            .roundOffManualLimit(BigDecimal.valueOf(10)) // default limit
                            .build();

                    System.out.println("TC: " + tc.id + " -> dp=" + config.getCurrencyDecimalPlaces() + ", raw=" + tc.profile.currencyDecimalPlaces);

                    // Convert items to CalculationLineRequest
                    List<CalculationLineRequest> lines = new ArrayList<>();
                    for (ScenarioItemDto item : tc.items) {
                        String lineDiscountType = null;
                        BigDecimal lineDiscountValue = BigDecimal.ZERO;
                        if (item.discountPercent != null) {
                            lineDiscountType = "PERCENT";
                            lineDiscountValue = item.discountPercent;
                        } else if (item.discountAmount != null) {
                            lineDiscountType = "AMOUNT";
                            lineDiscountValue = item.discountAmount;
                        } else if (item.discount != null) {
                            lineDiscountType = item.discount.type.toUpperCase();
                            lineDiscountValue = item.discount.value;
                        }

                        CalculationLineRequest lineReq = CalculationLineRequest.builder()
                                .quantity(item.quantity)
                                .unitPrice(item.price)
                                .taxRate(item.taxRate)
                                .taxType(item.taxType != null ? item.taxType : "EXCLUSIVE")
                                .isPackagedGood(item.isPackaged)
                                .lineDiscountType(lineDiscountType)
                                .lineDiscountValue(lineDiscountValue)
                                .discountAmount(lineDiscountType != null && lineDiscountType.equals("AMOUNT") ? lineDiscountValue : BigDecimal.ZERO)
                                .discountPercent(lineDiscountType != null && lineDiscountType.equals("PERCENT") ? lineDiscountValue : BigDecimal.ZERO)
                                .build();
                        System.out.println("Line: qty=" + lineReq.getQuantity() + ", price=" + lineReq.getUnitPrice() + ", rate=" + lineReq.getTaxRate() + ", type=" + lineReq.getTaxType() + ", isPackaged=" + lineReq.getIsPackagedGood() + ", discType=" + lineReq.getLineDiscountType() + ", discVal=" + lineReq.getLineDiscountValue());
                        lines.add(lineReq);
                    }

                    // Setup calculation request
                    BigDecimal requestedRoundOff = null;
                    if (tc.profile.roundOffConfig != null && "MANUAL".equalsIgnoreCase(tc.profile.roundOffConfig.roundOffMode)) {
                        requestedRoundOff = tc.profile.roundOffConfig.roundOffManualValue;
                    }

                    CalculationRequest request = CalculationRequest.builder()
                            .lines(lines)
                            .orderDiscountType(tc.orderDiscount.type)
                            .orderDiscountValue(tc.orderDiscount.value)
                            .requestedRoundOff(requestedRoundOff)
                            .roundOffMode(tc.profile.roundOffConfig != null ? tc.profile.roundOffConfig.roundOffMode : null)
                            .build();

                    if (Boolean.TRUE.equals(tc.expectsError)) {
                        try {
                            calculationService.calculate(request, config);
                            fail("Expected calculation service to throw exception for scenario " + tc.id);
                        } catch (BusinessException | IllegalArgumentException | IllegalStateException e) {
                            // Passed validation test
                        }
                    } else {
                        CalculationResult result = calculationService.calculate(request, config);
                        System.out.println("Result " + tc.id + ": gross=" + result.getGrossAmount() + ", taxable=" + result.getTaxableAmount() + ", tax=" + result.getTotalTax() + ", total=" + result.getGrandTotal() + ", roundoff=" + result.getRoundOffAmount());

                        // Global asserts
                        assertThat(result.getGrossAmount()).isEqualByComparingTo(tc.expected.grossAmount);
                        assertThat(result.getTaxableAmount()).isEqualByComparingTo(tc.expected.taxableAmount);
                        assertThat(result.getTotalTax()).isEqualByComparingTo(tc.expected.totalTax);
                        assertThat(result.getGrandTotal()).isEqualByComparingTo(tc.expected.grandTotal);
                        assertThat(result.getRoundOffAmount()).isEqualByComparingTo(tc.expected.roundOffAmount);

                        // Line item asserts
                        for (int i = 0; i < tc.expected.items.size(); i++) {
                            ExpectedLineDto expectedLine = tc.expected.items.get(i);
                            CalculatedLine actualLine = result.getLines().get(i);

                            assertThat(actualLine.getLineTotal()).isEqualByComparingTo(expectedLine.lineTotal);
                            assertThat(actualLine.getTaxableAmount()).isEqualByComparingTo(expectedLine.taxableAmount);
                            assertThat(actualLine.getTaxAmount()).isEqualByComparingTo(expectedLine.taxAmount);
                        }
                    }
                }
        ));
    }

    // Helper classes for scenario loading
    private static class ScenarioDto {
        public String id;
        public String description;
        public Boolean expectsError;
        public ProfileDto profile;
        public List<ScenarioItemDto> items;
        @JsonProperty("orderDiscount")
        public DiscountDto orderDiscount;
        public ExpectedDto expected;
    }

    private static class ProfileDto {
        @JsonProperty("tax_enabled")
        public boolean taxEnabled;
        @JsonProperty("default_tax_rate")
        public BigDecimal defaultTaxRate = BigDecimal.ZERO;
        @JsonProperty("prices_include_tax")
        public boolean pricesIncludeTax;
        @JsonProperty("currencyDecimalPlaces")
        public Integer currencyDecimalPlaces = 2;
        @JsonProperty("round_off_config")
        public RoundOffConfigDto roundOffConfig;
    }

    private static class RoundOffConfigDto {
        @JsonProperty("round_off_enabled")
        public boolean roundOffEnabled;
        @JsonProperty("round_off_mode")
        public String roundOffMode;
        @JsonProperty("round_off_auto_factor")
        public BigDecimal roundOffAutoFactor;
        @JsonProperty("round_off_manual_value")
        public BigDecimal roundOffManualValue;
    }

    private static class ScenarioItemDto {
        public BigDecimal price;
        public BigDecimal quantity;
        @JsonProperty("tax_rate")
        public BigDecimal taxRate = BigDecimal.ZERO;
        @JsonProperty("tax_type")
        public String taxType;
        @JsonProperty("is_packaged")
        public boolean isPackaged;
        @JsonProperty("discount_percent")
        public BigDecimal discountPercent;
        @JsonProperty("discount_amount")
        public BigDecimal discountAmount;
        public DiscountDto discount;
    }

    private static class DiscountDto {
        public String type;
        public BigDecimal value;
    }

    private static class ExpectedDto {
        @JsonProperty("gross_amount")
        public BigDecimal grossAmount;
        @JsonProperty("taxable_amount")
        public BigDecimal taxableAmount;
        @JsonProperty("total_tax")
        public BigDecimal totalTax;
        @JsonProperty("grand_total")
        public BigDecimal grandTotal;
        @JsonProperty("round_off_amount")
        public BigDecimal roundOffAmount;
        public List<ExpectedLineDto> items;
    }

    private static class ExpectedLineDto {
        @JsonProperty("line_total")
        public BigDecimal lineTotal;
        @JsonProperty("taxable_amount")
        public BigDecimal taxableAmount;
        @JsonProperty("tax_amount")
        public BigDecimal taxAmount;
    }
}
