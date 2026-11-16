package com.restaurant.pos.purchase.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of the India GST purchase register (inward supplies).
 * Carries both invoice-level (supplier, GSTIN, place of supply, invoice value) and
 * line-level (HSN, UQC, qty, taxable value, CGST/SGST/IGST) data so the register
 * can be filed / reconciled against GSTR-2B and imported into accounting tools.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Line-level India GST purchase register row")
public class PurchaseGstRegisterRowDto {

    // ── Invoice level ───────────────────────────────────────────
    private UUID orderId;
    private String orderNo;
    @Schema(description = "Supplier's invoice number (captured as reference on the purchase)")
    private String supplierInvoiceNo;
    private Instant invoiceDate;
    private String orderStatus;
    private String paymentStatus;
    private String paymentMethod;

    private UUID vendorId;
    private String supplierName;
    private String supplierGstin;
    @Schema(description = "Registered / Unregistered / Invalid GSTIN")
    private String supplierRegistrationType;
    private String supplierStateCode;
    private String supplierState;
    private String supplierAddress;

    private UUID orgId;
    private String branchName;
    @Schema(description = "Recipient (own branch) GSTIN")
    private String recipientGstin;
    private String recipientStateCode;
    private String recipientState;

    @Schema(description = "Place of supply in portal format, e.g. 27-Maharashtra")
    private String placeOfSupply;
    @Schema(description = "INTRA_STATE or INTER_STATE")
    private String supplyType;
    @Schema(description = "Y/N — reverse charge applicability")
    private String reverseCharge;
    private String invoiceType;
    private BigDecimal invoiceValue;
    private BigDecimal invoiceTaxableValue;
    private BigDecimal invoiceTotalTax;
    private BigDecimal invoiceRoundOff;

    // ── Line level ──────────────────────────────────────────────
    private Integer lineNo;
    private UUID productId;
    private String productCode;
    private String itemName;
    private String categoryName;
    @Schema(description = "HSN / SAC code from product master")
    private String hsnCode;
    private String unitOfMeasure;
    @Schema(description = "GST Unique Quantity Code, e.g. KGS-KILOGRAMS")
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
    @Schema(description = "SGST or UTGST rate depending on recipient state")
    private BigDecimal sgstRate;
    private BigDecimal sgstAmount;
    private BigDecimal cessAmount;
    private BigDecimal totalTax;
    private BigDecimal lineTotal;

    @Schema(description = "ITC eligibility classification: Inputs / Capital goods / Input services / Ineligible")
    private String itcEligibility;
    private BigDecimal itcIgst;
    private BigDecimal itcCgst;
    private BigDecimal itcSgst;
    private BigDecimal itcCess;

    @Schema(description = "Data-quality warnings that may block correct filing (missing GSTIN, HSN, etc.)")
    private String remarks;
}
