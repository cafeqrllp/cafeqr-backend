package com.restaurant.pos.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigurationDto {
    // Power Modules
    private boolean onlinePaymentEnabled;
    private boolean menuImagesEnabled;
    private boolean creditEnabled;
    @Builder.Default
    private String creditAllocationMode = "OLDEST_FIRST";
    private boolean tableManagementEnabled;
    private boolean qrOrderingEnabled;
    private boolean inventoryEnabled;
    private boolean productionEnabled;
    private boolean customersEnabled;
    private boolean loyaltyEnabled;
    private boolean sendToKitchenEnabled;
    private boolean onlineDeliveryEnabled;
    private boolean allowMultipleCustomersPerOrder;
    private boolean posProductListingEnabled;
    private boolean discountEnabled;
    @Builder.Default
    private String defaultBillingUiMode = "standard";

    // Offline Sync Capabilities
    private boolean offlineSyncEnabled;
    private Integer offlineSyncInterval;
    private Integer offlineLeaseBlockSize;
    private boolean offlineFailOpenPayments;
    private boolean offlineLocalEncryption;

    // Tax Settings (Stubbed for now as requested - handled in client wise)
    @Builder.Default
    private boolean taxEnabled = false;
    @Builder.Default
    private String taxLabelGlobal = "GST";
    @Builder.Default
    private List<Object> taxRates = Collections.emptyList();
    private String taxDefaultId;
    @Builder.Default
    private boolean pricesIncludeTax = false;
    @Builder.Default
    private boolean taxSplitEnabled = true;

    // Locale
    private String currencySymbol;
    private String currencyPosition;

    // Round-off
    private boolean roundOffEnabled;
    private String roundOffMode;
    private BigDecimal roundOffAutoFactor;
    private BigDecimal roundOffManualLimit;

    // Receipt (Logo & Footer)
    private String billFooter;
    private String printLogoBitmap;
    private Integer printLogoCols;
    private Integer printLogoRows;

    // Hardware & Paper Settings
    private String paperMm;
    private Integer printCols;
    private Integer printLeftMarginDots;
    private Integer printRightMarginDots;
    private boolean printAutoCut;
    private String printWinListUrl;
    private String printWinPostUrl;

    // Branch override metadata (not persisted — set by service layer)
    @Builder.Default
    private boolean branchOverride = false;
}
