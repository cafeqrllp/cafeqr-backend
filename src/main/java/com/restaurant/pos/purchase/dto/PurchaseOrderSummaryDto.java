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
 * Lightweight summary DTO for Purchase Order history list rows.
 * Only contains fields needed for table rendering - NO line items, NO user resolution, NO invoice sub-queries.
 * Full detail (lines + user names + linked docs) is fetched lazily via GET /api/v1/purchase/orders/{id}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Lightweight summary of a Purchase Order for history list display")
public class PurchaseOrderSummaryDto {

    @Schema(description = "Unique UUID of the purchase order")
    private UUID id;

    @Schema(description = "Purchase order number")
    private String orderNo;

    @Schema(description = "Current order workflow status")
    private String orderStatus;

    @Schema(description = "Whether goods have been received into stock")
    private Boolean isReceived;

    @Schema(description = "Current payment status (PENDING, PARTIAL, PAID)")
    private String paymentStatus;

    @Schema(description = "Payment method used (CASH, CREDIT, BANK_TRANSFER, etc.)")
    private String paymentMethod;

    @Schema(description = "Supplier/vendor UUID")
    private UUID vendorId;

    @Schema(description = "Supplier/vendor name (resolved from vendors table)")
    private String vendorName;

    @Schema(description = "Receiving warehouse UUID")
    private UUID warehouseId;

    @Schema(description = "Receiving warehouse name (resolved from warehouses table)")
    private String warehouseName;

    @Schema(description = "Branch/Organization UUID")
    private UUID orgId;

    @Schema(description = "Order date and time")
    private Instant orderDate;

    @Schema(description = "Final grand total amount")
    private BigDecimal grandTotal;

    @Schema(description = "Total tax amount")
    private BigDecimal totalTaxAmount;

    @Schema(description = "Total discount amount")
    private BigDecimal totalDiscountAmount;

    @Schema(description = "Total base amount before tax and discount")
    private BigDecimal totalAmount;

    @Schema(description = "Order created timestamp")
    private Instant createdAt;

    @Schema(description = "External reference or PO number")
    private String reference;

    @Schema(description = "Number of line items on the order")
    private Integer itemCount;

    @Schema(description = "Revision number — 0 for original, incremented on each edit")
    private Integer revisionNumber;

    @Schema(description = "UUID of the original order this was revised from")
    private UUID originalOrderId;
}
