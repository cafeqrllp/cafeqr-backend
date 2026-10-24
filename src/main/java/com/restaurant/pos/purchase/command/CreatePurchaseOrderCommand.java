package com.restaurant.pos.purchase.command;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CQRS Command model for creating a Purchase Order.
 * Enforces mandatory vendor and warehouse fields required for procurement.
 */
@Data
@Schema(description = "Command payload for creating a Purchase Order")
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class CreatePurchaseOrderCommand {

    @NotNull(message = "Vendor must be specified for a Purchase Order")
    @Schema(description = "Supplier/vendor UUID", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID vendorId;

    @NotNull(message = "Warehouse must be specified for a Purchase Order")
    @Schema(description = "Destination warehouse UUID for stock intake", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID warehouseId;

    @Schema(description = "Branch/Organization UUID")
    private UUID orgId;

    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
    @Schema(description = "Order date/time (ISO-8601 UTC Instant). Defaults to now if omitted.", example = "2026-05-26T10:00:00Z")
    private Instant orderDate;

    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
    @Schema(description = "Expected delivery date/time (ISO-8601 UTC Instant)")
    private Instant expectedDate;

    @Schema(description = "Initial order status (DRAFT or CONFIRMED). Defaults to DRAFT if omitted.", example = "DRAFT")
    private String orderStatus;

    @Schema(description = "Optional non-mandatory flag indicating if goods have been received at warehouse", example = "false")
    private Boolean isReceived;

    @Schema(description = "Payment status (PENDING, PARTIAL, PAID). Defaults to PENDING if omitted.", example = "PENDING")
    private String paymentStatus;

    @Schema(description = "Payment method (CASH, CREDIT, BANK_TRANSFER, etc.)", example = "CREDIT")
    private String paymentMethod;

    @Schema(description = "Optional split payment breakdown when paymentMethod is MIXED")
    private List<com.restaurant.pos.order.dto.CreateOrderRequest.PaymentSplitRequest> paymentSplits;

    @Schema(description = "External purchase reference number or supplier invoice number")
    private String reference;

    @Schema(description = "Internal notes or description for this Purchase Order")
    private String description;

    @Schema(description = "Pre-calculated subtotal before tax", example = "1000.00")
    private BigDecimal totalAmount;

    @Schema(description = "Pre-calculated total tax amount", example = "180.00")
    private BigDecimal totalTaxAmount;

    @Schema(description = "Pre-calculated total discount amount", example = "0.00")
    private BigDecimal totalDiscountAmount;

    @Schema(description = "Grand total payable to vendor", example = "1180.00")
    private BigDecimal grandTotal;

    @Schema(description = "Client-generated idempotency reference for deduplication")
    private String sourceLocalRef;

    @NotEmpty(message = "Purchase Order must have at least one line item")
    @Valid
    @Schema(description = "List of purchase order line items", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<PurchaseOrderLineCommand> lines;

    @Data
    @Schema(description = "Purchase Order line item command")
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class PurchaseOrderLineCommand {

        @NotNull(message = "Product UUID must not be null")
        @Schema(description = "Product UUID", requiredMode = Schema.RequiredMode.REQUIRED)
        private UUID productId;

        @Schema(description = "Product variant UUID if applicable")
        private UUID variantId;

        @Schema(description = "Product name (for display/denormalization)")
        private String productName;

        @NotNull(message = "Quantity must not be null")
        @DecimalMin(value = "0.01", message = "Quantity must be greater than zero")
        @Schema(description = "Quantity to purchase", example = "50.00", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal quantity;

        @NotNull(message = "Unit price must not be null")
        @DecimalMin(value = "0.00", message = "Unit price must not be negative")
        @Schema(description = "Purchase unit price per item", example = "20.00", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal unitPrice;

        @Schema(description = "Unit of measure (e.g. KG, PCS, BOX)", example = "KG")
        private String unitOfMeasure;

        @Schema(description = "UOM precision (decimal places) of the product, 0-4")
        private Integer uomPrecision;

        @Schema(description = "Tax rate percentage", example = "18.00")
        private BigDecimal taxRate;

        @Schema(description = "Calculated tax amount", example = "180.00")
        private BigDecimal taxAmount;

        @Schema(description = "Discount amount on this line", example = "0.00")
        private BigDecimal discountAmount;

        @Schema(description = "Calculated line total (qty × unitPrice + tax − discount)", example = "1180.00")
        private BigDecimal lineTotal;

        @Schema(description = "Line notes or description")
        private String description;
    }
}
