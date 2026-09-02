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

    @Schema(description = "Whether purchase/goods receipt is received")
    private Boolean isReceived;

    @Schema(description = "Current payment status")
    private String paymentStatus;

    @Schema(description = "Source origin of the order")
    private String orderSource;

    @Schema(description = "Branch/Organization UUID")
    private UUID orgId;

    @Schema(description = "Point of sale terminal UUID")
    private UUID terminalId;

    @Schema(description = "Point of sale terminal code")
    private String terminalCode;

    @Schema(description = "Point of sale terminal display name")
    private String terminalName;

    @Schema(description = "Offline synchronization origin")
    private String syncOrigin;

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

    @Schema(description = "Order remarks or kitchen instructions")
    private String remarks;

    @Schema(description = "Payment reference or code")
    private String reference;

    @Schema(description = "Associated customer details list")
    private List<OrderCustomerDto> customers;

    @JsonProperty("isCredit")
    @Schema(description = "Whether this order was completed as a credit sale")
    private Boolean isCredit;

    @Schema(description = "Customer UUID linked to the order")
    private UUID customerId;

    @Schema(description = "Credit customer UUID linked to the credit sale workflow")
    private UUID creditCustomerId;

    @Schema(description = "Customer display name")
    private String customerName;

    @Schema(description = "Customer phone number")
    private String customerPhone;

    @Schema(description = "Credit customer name")
    private String creditCustomerName;

    @Schema(description = "Credit customer phone")
    private String creditCustomerPhone;

    @Schema(description = "Customer current loyalty points balance")
    private Integer customerLoyaltyPoints;

    @Schema(description = "Associated invoice document number")
    private String invoiceNo;

    @Schema(description = "Associated daily sequential bill number")
    private Integer dailyBillNo;

    @Schema(description = "Associated payment record identifier")
    private String paymentNo;

    @Schema(description = "Associated payment method")
    private String paymentMethod;

    @Schema(description = "Cash split amount if mixed payment")
    private BigDecimal cashAmount;

    @Schema(description = "Online split amount if mixed payment")
    private BigDecimal onlineAmount;

    @Schema(description = "Pre-discount gross amount")
    private BigDecimal grossAmount;

    @Schema(description = "Round-off adjustment applied at settlement")
    private BigDecimal roundOffAmount;

    @Schema(description = "Loyalty points redeemed on this order")
    private Integer redeemPoints;

    @Schema(description = "Monetary discount value of loyalty points redeemed")
    private BigDecimal loyaltyAmount;

    @Schema(description = "Order discount type (PERCENT, AMOUNT)")
    private String orderDiscountType;

    @Schema(description = "Order discount value")
    private BigDecimal orderDiscountValue;

    @Schema(description = "Discount source (MANUAL, SYSTEM, etc.)")
    private String discountSource;

    @Schema(description = "Sanitized, non-circular list of order item lines")
    private List<OrderLineResponseDto> lines;

    @Schema(description = "Revision number — 0 for original, incremented on each edit")
    private Integer revisionNumber;

    @Schema(description = "UUID of the original order this was revised from (null if not a revision)")
    private UUID originalOrderId;

    @Schema(description = "User who created the order")
    private String createdBy;

    @Schema(description = "User who last updated the order")
    private String updatedBy;

    @Schema(description = "Timezone of the branch/organization where the order was created")
    private String timezone;

    @Schema(description = "Date and time the order was created")
    private Instant createdAt;

    @Schema(description = "Date and time the order was last updated")
    private Instant updatedAt;

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

        @Schema(description = "Calculated line total (quantity x unitPrice + tax - discount)")
        private BigDecimal lineTotal;

        @Schema(description = "Product name (denormalized for display)")
        private String productName;

        @Schema(description = "Unit of measure (e.g. KG, PCS, BOX)")
        private String unitOfMeasure;

        @Schema(description = "UOM precision/decimals")
        private Integer uomPrecision;

        @Schema(description = "Line notes")
        private String description;

        @Schema(description = "Pre-discount gross line amount")
        private BigDecimal grossLineAmount;

        @Schema(description = "Unit price ex tax")
        private BigDecimal unitPriceExTax;

        @Schema(description = "Taxable amount")
        private BigDecimal taxableAmount;

        @Schema(description = "Tax type (INCLUSIVE, EXCLUSIVE, NONE)")
        private String taxType;

        @Schema(description = "Tax snapshot rate")
        private BigDecimal taxSnapshotRate;

        @Schema(description = "Tax code")
        private String taxCode;

        @Schema(description = "Tax name")
        private String taxName;

        @Schema(description = "Manual discount amount")
        private BigDecimal manualDiscountAmount;

        @Schema(description = "Manual discount percent")
        private BigDecimal manualDiscountPercent;

        @Schema(description = "Allocated order discount")
        private BigDecimal allocatedOrderDiscount;

        @Schema(description = "Whether the product is a packaged good")
        @JsonProperty("isPackagedGood")
        private Boolean isPackagedGood;
    }
}
