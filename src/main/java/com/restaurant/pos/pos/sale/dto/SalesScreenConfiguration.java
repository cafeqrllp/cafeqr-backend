package com.restaurant.pos.pos.sale.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * Dedicated, lean configuration DTO for POS Sales Screen.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesScreenConfiguration {

    private String salesType; // "STANDARD" | "COUNTER"
    private String defaultBillingUiMode; // "standard" | "counter"

    /**
     * Monotonically increasing version number for POS configuration.
     * POS terminals can compare this against their local version to detect staleness.
     * Derived from the configuration table's updatedAt timestamp (epoch millis).
     */
    private Long configurationVersion;
    private boolean posProductListingEnabled;

    private boolean tableEnabled;
    private boolean customerEnabled;
    private boolean waiterEnabled;
    private boolean discountEnabled;
    private boolean barcodeScannerEnabled;
    private boolean onlineDeliveryEnabled;
    private boolean takeawayAutoPrintKotOnSettle;
    private boolean dineInAutoPrintKotOnSettle;
    private boolean takeawayHideKitchenMode;
    private boolean dineInHideKitchenMode;
    private boolean sendToKitchenEnabled;
    private boolean loyaltyEnabled;
    private boolean menuImagesEnabled;
    private boolean creditEnabled;
    private String creditAllocationMode;

    // Currency & Formatting
    private String currencySymbol;
    private String currencyPosition;
    private Integer currencyDecimalPlaces;

    // Round-off
    private boolean roundOffEnabled;
    private String roundOffMode;
    private BigDecimal roundOffAutoFactor;
    private BigDecimal roundOffManualLimit;

    // Tax Settings
    private boolean taxEnabled;
    private String taxLabelGlobal;
    private boolean pricesIncludeTax;
    private boolean taxSplitEnabled;
    private List<Object> taxRates;

    public String getSalesType() {
        return salesType != null && !salesType.trim().isEmpty() ? salesType : "STANDARD";
    }

    public String getDefaultBillingUiMode() {
        return defaultBillingUiMode != null && !defaultBillingUiMode.trim().isEmpty() ? defaultBillingUiMode : "standard";
    }

    public Integer getCurrencyDecimalPlaces() {
        return currencyDecimalPlaces != null ? currencyDecimalPlaces : 2;
    }

    public List<Object> getTaxRates() {
        return taxRates != null ? taxRates : Collections.emptyList();
    }

    public boolean isTableManagementEnabled() {
        return tableEnabled;
    }

    public void setTableManagementEnabled(boolean tableManagementEnabled) {
        this.tableEnabled = tableManagementEnabled;
    }

    public boolean isCustomersEnabled() {
        return customerEnabled;
    }

    public void setCustomersEnabled(boolean customersEnabled) {
        this.customerEnabled = customersEnabled;
    }
}
