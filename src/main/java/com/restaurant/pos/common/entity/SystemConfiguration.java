package com.restaurant.pos.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "system_configurations")
@EntityListeners(AuditingEntityListener.class)
public class SystemConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "org_id")
    private UUID orgId;

    // Power Modules (Feature Toggles)
    private boolean onlinePaymentEnabled;
    private boolean menuImagesEnabled;
    private boolean creditEnabled;
    @Column(name = "credit_allocation_mode", length = 30)
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
    private boolean customerAgeEnabled;
    private boolean posProductListingEnabled;
    private boolean discountEnabled;
    @Column(name = "default_billing_ui_mode", length = 20)
    @Builder.Default
    private String defaultBillingUiMode = "standard";

    // Offline Sync Capabilities
    private boolean offlineSyncEnabled;
    private Integer offlineSyncInterval;
    private Integer offlineLeaseBlockSize;
    private boolean offlineFailOpenPayments;
    private boolean offlineLocalEncryption;

    // Round-off Engine
    private boolean roundOffEnabled;
    private String roundOffMode;
    private BigDecimal roundOffAutoFactor;
    private BigDecimal roundOffManualLimit;

    // Locale & Global Logic
    private boolean taxEnabled;
    private String taxLabelGlobal;
    @Column(columnDefinition = "TEXT")
    private String taxRatesJson;
    private String taxDefaultId;
    private boolean pricesIncludeTax;
    private boolean taxSplitEnabled;
    private String currencySymbol;
    private String currencyPosition;

    // Receipt Customization
    @Column(columnDefinition = "TEXT")
    private String billFooter;
    @Column(columnDefinition = "TEXT")
    private String printLogoBitmap;
    private Integer printLogoCols;
    private Integer printLogoRows;

    // Hardware & Formatting Settings
    private String paperMm;
    private Integer printCols;
    private Integer printLeftMarginDots;
    private Integer printRightMarginDots;
    private boolean printAutoCut;
    private String printWinListUrl;
    private String printWinPostUrl;

    // Auditing
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @CreatedBy
    private String createdBy;
}
