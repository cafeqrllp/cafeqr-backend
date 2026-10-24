package com.restaurant.pos.invoice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicDigitalInvoiceDto {

    private UUID invoiceId;
    private UUID orderId;
    private String invoiceNo;
    private String orderNo;
    private Integer dailyBillNo;
    private LocalDateTime invoiceDate;
    private String status;
    private String fulfillmentType;
    private String tableNumber;
    private String customerName;
    private String customerPhone;
    private String customerAddress;
    private String customerGstin;
    private String paymentMethod;
    private String paymentStatus;

    // Currency & Tax settings
    private String currencySymbol;
    private Integer currencyDecimals;
    private String taxLabel; // GST, VAT, etc.

    // Totals
    private BigDecimal grossAmount;
    private BigDecimal totalDiscountAmount;
    private BigDecimal subtotal;
    private BigDecimal totalTaxAmount;
    private BigDecimal cgstRate;
    private BigDecimal cgstAmount;
    private BigDecimal sgstRate;
    private BigDecimal sgstAmount;
    private BigDecimal roundOffAmount;
    private BigDecimal grandTotal;

    // Lines
    private List<LineItemDto> lines;

    // Payment breakdown (especially for MIXED payments)
    private List<PaymentSplitDto> paymentSplits;

    // Store Branding & Contact
    private String storeName;
    private String logoUrl;
    private String storeAddress;
    private String storePhone;
    private String gstin;
    private String fssaiNumber;
    private String googleMapsUrl;
    private String billFooter;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineItemDto {
        private UUID productId;
        private String productName;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal taxRate;
        private BigDecimal lineTotal;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentSplitDto {
        private String paymentMethod;
        private BigDecimal amount;
        private String referenceNo;
    }
}
