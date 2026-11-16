package com.restaurant.pos.order.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of the India GST sales register (outward supplies) used for GSTR-1 filing.
 * Carries invoice-level data (customer GSTIN, B2B/B2C type, place of supply, invoice value)
 * and line-level data (HSN, UQC, qty, taxable value, CGST/SGST/IGST).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Line-level India GST sales register row")
public class SalesGstRegisterRowDto {

    // ── Invoice level ───────────────────────────────────────────
    private UUID orderId;
    private String invoiceNo;
    private String orderNo;
    private Instant invoiceDate;
    private String paymentMethod;

    private UUID orgId;
    private String branchName;
    @Schema(description = "Supplier (own branch) GSTIN")
    private String branchGstin;
    private String branchStateCode;
    private String branchState;

    private String customerName;
    private String customerGstin;
    @Schema(description = "B2B (customer has GSTIN) or B2CS (unregistered customer)")
    private String supplyCategory;
    private String customerStateCode;
    private String customerState;

    @Schema(description = "Place of supply in portal format, e.g. 32-Kerala")
    private String placeOfSupply;
    @Schema(description = "INTRA_STATE or INTER_STATE")
    private String supplyType;
    private String reverseCharge;
    private String invoiceType;
    private BigDecimal invoiceValue;
    private BigDecimal invoiceTaxableValue;
    private BigDecimal invoiceTotalTax;
    private BigDecimal invoiceDiscount;

    // ── Line level ──────────────────────────────────────────────
    private Integer lineNo;
    private UUID productId;
    private String productCode;
    private String itemName;
    private String categoryName;
    @Schema(description = "HSN / SAC code from product master")
    private String hsnCode;
    private String unitOfMeasure;
    @Schema(description = "GST Unique Quantity Code, e.g. NOS-NUMBERS")
    private String uqc;
    private BigDecimal quantity;
    @Schema(description = "Per-unit rate excluding GST")
    private BigDecimal rateExTax;
    private BigDecimal discountAmount;
    private BigDecimal taxableValue;
    private BigDecimal gstRate;
    private BigDecimal igstRate;
    private BigDecimal igstAmount;
    private BigDecimal cgstRate;
    private BigDecimal cgstAmount;
    @Schema(description = "SGST or UTGST rate depending on branch state")
    private BigDecimal sgstRate;
    private BigDecimal sgstAmount;
    private BigDecimal cessAmount;
    private BigDecimal totalTax;
    private BigDecimal lineTotal;

    @Schema(description = "Data-quality warnings that may block correct filing (missing GSTIN, HSN, etc.)")
    private String remarks;
}
