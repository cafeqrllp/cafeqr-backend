package com.restaurant.pos.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.restaurant.pos.order.domain.OrderType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "High-fidelity sanitized order response presentation model")
public class OrderResponseDto {
    @Schema(description = "Unique UUID of the order")
    private UUID id;

    @Schema(description = "Order number")
    private String orderNo;

    @Schema(description = "Type of order (SALE, PURCHASE, EXPENSE)")
    private OrderType orderType;

    @Schema(description = "Current order workflow status")
    private String orderStatus;

    @Schema(description = "Current payment status")
    private String paymentStatus;

    @Schema(description = "Source origin of the order")
    private String orderSource;

    @Schema(description = "Dining table UUID if applicable")
    private UUID tableId;

    @Schema(description = "Dining table number if applicable")
    private String tableNumber;

    @Schema(description = "Storage warehouse UUID")
    private UUID warehouseId;

    @Schema(description = "Supplier/vendor UUID if purchase order")
    private UUID vendorId;

    @Schema(description = "Profile currency UUID")
    private UUID currencyId;

    @Schema(description = "Date and time the order was placed")
    private Instant orderDate;

    @Schema(description = "Sum of tax amounts of all items")
    private BigDecimal totalTaxAmount;

    @Schema(description = "Total discount amount applied")
    private BigDecimal totalDiscountAmount;

    @Schema(description = "Total base amount before tax and discount")
    private BigDecimal totalAmount;

    @Schema(description = "Final grand total amount after tax and discount")
    private BigDecimal grandTotal;

    @Schema(description = "Fulfillment type (DINE_IN, TAKEAWAY, etc.)")
    private String fulfillmentType;

    @Schema(description = "Description or general operation notes")
    private String description;

    @Schema(description = "Payment reference or code")
    private String reference;

    @Schema(description = "Associated customer details list")
    private List<OrderCustomerDto> customers;

    @JsonProperty("isCredit")
    @Schema(description = "Whether this order was completed as a credit sale")
    private Boolean isCredit;

    @Schema(description = "Credit customer UUID linked to the credit sale workflow")
    private UUID creditCustomerId;

    @Schema(description = "Associated invoice document number")
    private String invoiceNo;

    @Schema(description = "Associated payment record identifier")
    private String paymentNo;

    @Schema(description = "Associated payment method")
    private String paymentMethod;

    @Schema(description = "Sanitized, non-circular list of order item lines")
    private List<OrderLineResponseDto> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Sanitized order line response details")
    public static class OrderLineResponseDto {
        @Schema(description = "Line item unique UUID")
        private UUID id;

        @Schema(description = "Associated Product UUID")
        private UUID productId;

        @Schema(description = "Associated Product Variant UUID")
        private UUID variantId;

        @Schema(description = "Quantity ordered")
        private BigDecimal quantity;

        @Schema(description = "Unit price")
        private BigDecimal unitPrice;

        @Schema(description = "Tax rate percentage")
        private BigDecimal taxRate;

        @Schema(description = "Total tax amount for this line")
        private BigDecimal taxAmount;

        @Schema(description = "Total discount amount for this line")
        private BigDecimal discountAmount;

        @Schema(description = "Calculated line total (quantity × unitPrice + tax - discount)")
        private BigDecimal lineTotal;

        @Schema(description = "Product name (denormalized for display)")
        private String productName;

        @Schema(description = "Unit of measure (e.g. KG, PCS, BOX)")
        private String unitOfMeasure;

        @Schema(description = "Line notes")
        private String description;
    }
}
