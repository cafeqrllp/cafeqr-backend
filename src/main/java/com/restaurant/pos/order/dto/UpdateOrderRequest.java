package com.restaurant.pos.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "DTO for updating an existing order")
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateOrderRequest {

    @Schema(description = "Dining table UUID")
    private UUID tableId;

    @Schema(description = "Dining table number")
    private String tableNumber;

    @Schema(description = "Warehouse UUID for inventory tracking")
    private UUID warehouseId;

    @Schema(description = "Supplier/vendor UUID for purchase orders")
    private UUID vendorId;

    @Schema(description = "Order status (DRAFT, CONFIRMED, COMPLETED, CANCELLED)")
    private String orderStatus;

    @Schema(description = "Payment status (PENDING, PARTIAL, PAID)")
    private String paymentStatus;

    @Schema(description = "Operational description or notes")
    private String description;

    @Schema(description = "Order remarks or kitchen notes")
    private String remarks;

    @Schema(description = "Reference ID/No")
    private String reference;

    @Schema(description = "Payment method/mode (CASH, BANK_TRANSFER, UPI, CARD, etc.)")
    private String paymentMethod;

    @Schema(description = "Fulfillment type (DINE_IN, TAKEAWAY, DELIVERY)", example = "DINE_IN")
    private String fulfillmentType;

    @Schema(description = "Dynamic list of customer details linked to this order")
    private com.fasterxml.jackson.databind.JsonNode customerIds;

    @JsonProperty("isCredit")
    @Schema(description = "Whether this order is being completed as a credit sale")
    private Boolean isCredit;

    @Schema(description = "Credit customer UUID for credit sale workflows")
    private UUID creditCustomerId;

    @Schema(description = "Pre-calculated total before tax/discount", example = "100.00")
    private BigDecimal totalAmount;

    @Schema(description = "Pre-calculated tax amount", example = "18.00")
    private BigDecimal totalTaxAmount;

    @Schema(description = "Pre-calculated discount amount", example = "0.00")
    private BigDecimal totalDiscountAmount;

    @Schema(description = "Grand total payable", example = "118.00")
    private BigDecimal grandTotal;

    @Schema(description = "Round-off adjustment applied to this settlement", example = "0.00")
    private BigDecimal roundOffAmount;

    @Schema(description = "Sum of gross_line_amount across all lines (pre-discount face total)", example = "200.00")
    private BigDecimal grossAmount;

    @Schema(description = "Order-level discount type entered by user: PERCENT or AMOUNT", example = "PERCENT")
    private String orderDiscountType;

    @Schema(description = "Order-level discount value entered by user (e.g. 10 for 10%, or 100.00 for flat discount)", example = "10")
    private BigDecimal orderDiscountValue;

    @Schema(description = "Originating source of this discount: MANUAL, QR, COUPON, PROMOTION, LOYALTY", example = "MANUAL")
    private String discountSource;

    @Valid
    @Schema(description = "List of updated order lines")
    private List<CreateOrderRequest.CreateOrderLineRequest> lines;

    @Schema(description = "Transient print kinds that this terminal will print locally, e.g. KOT or BILL")
    private List<String> skipAutoPrintKinds;
}
