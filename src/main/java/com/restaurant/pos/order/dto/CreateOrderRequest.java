package com.restaurant.pos.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.restaurant.pos.order.domain.OrderType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "DTO for creating a new order")
public class CreateOrderRequest {

    @NotNull(message = "Order type must not be null")
    @Schema(description = "Type of the order", example = "SALE", requiredMode = Schema.RequiredMode.REQUIRED)
    private OrderType orderType;

    @Schema(description = "Legacy local offline order number")
    private String orderNo;

    @Schema(description = "Optional offline invoice number")
    private String offlineInvoiceNo;

    @Schema(description = "Optional offline payment number")
    private String offlinePaymentNo;

    @Schema(description = "Target dining table UUID")
    private UUID tableId;

    @Schema(description = "Dining table number")
    private String tableNumber;

    @Schema(description = "Warehouse UUID for inventory tracking")
    private UUID warehouseId;

    @Schema(description = "Supplier/vendor UUID for purchase orders")
    private UUID vendorId;

    @Schema(description = "Pricelist UUID for tax and rate calculations")
    private UUID pricelistId;

    @Schema(description = "Currency UUID")
    private UUID currencyId;

    @Schema(description = "Fulfillment type (DINE_IN, TAKEAWAY, DELIVERY)", example = "DINE_IN")
    private String fulfillmentType;

    @Schema(description = "Dynamic list of customer details linked to this order")
    private com.fasterxml.jackson.databind.JsonNode customerIds;

    @JsonProperty("isCredit")
    @Schema(description = "Whether this order is being completed as a credit sale")
    private Boolean isCredit;

    @Schema(description = "Credit customer UUID for credit sale workflows")
    private UUID creditCustomerId;

    @Schema(description = "Operational description or notes")
    private String description;

    @Schema(description = "Reference ID/No")
    private String reference;

    @Schema(description = "Payment method/mode (CASH, BANK_TRANSFER, UPI, CARD, etc.)")
    private String paymentMethod;

    @Schema(description = "Order date/time (ISO-8601 UTC Instant)", example = "2026-05-26T10:00:00Z")
    private Instant orderDate;

    @Schema(description = "Initial order status (DRAFT, CONFIRMED). Defaults to DRAFT if omitted.", example = "DRAFT")
    private String orderStatus;

    @Schema(description = "Initial payment status (PENDING, PARTIAL, PAID). Defaults to PENDING if omitted.", example = "PENDING")
    private String paymentStatus;

    @Schema(description = "Pre-calculated total before tax/discount", example = "100.00")
    private BigDecimal totalAmount;

    @Schema(description = "Pre-calculated tax amount", example = "18.00")
    private BigDecimal totalTaxAmount;

    @Schema(description = "Pre-calculated discount amount", example = "0.00")
    private BigDecimal totalDiscountAmount;

    @Schema(description = "Grand total payable", example = "118.00")
    private BigDecimal grandTotal;

    @NotEmpty(message = "Order lines must not be empty")
    @Valid
    @Schema(description = "List of order items/lines", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<CreateOrderLineRequest> lines;

    @Data
    @Schema(description = "DTO for creating an order line/item")
    public static class CreateOrderLineRequest {
        @NotNull(message = "Product UUID must not be null")
        @Schema(description = "Product UUID", requiredMode = Schema.RequiredMode.REQUIRED)
        private UUID productId;

        @Schema(description = "Product variant UUID if applicable")
        private UUID variantId;

        @NotNull(message = "Quantity must not be null")
        @DecimalMin(value = "0.01", message = "Quantity must be greater than zero")
        @Schema(description = "Quantity ordered", example = "2.00", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal quantity;

        @NotNull(message = "Unit price must not be null")
        @DecimalMin(value = "0.00", message = "Unit price must not be negative")
        @Schema(description = "Unit price per product item", example = "10.50", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal unitPrice;

        @Schema(description = "Tax rate percentage applied", example = "18.00")
        private BigDecimal taxRate;

        @Schema(description = "Calculated tax amount applied", example = "1.89")
        private BigDecimal taxAmount;

        @Schema(description = "Calculated discount amount applied", example = "0.00")
        private BigDecimal discountAmount;

        @Schema(description = "Calculated line total", example = "21.00")
        private BigDecimal lineTotal;

        @Schema(description = "Product name (for display/denormalization)")
        private String productName;

        @Schema(description = "Unit of measure (e.g. KG, PCS, BOX)")
        private String unitOfMeasure;

        @Schema(description = "Line summary or operational notes")
        private String description;
    }
}
