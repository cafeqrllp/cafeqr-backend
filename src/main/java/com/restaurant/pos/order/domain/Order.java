package com.restaurant.pos.order.domain;

import com.restaurant.pos.common.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.restaurant.pos.order.dto.OrderCustomerDto;
import com.restaurant.pos.order.dto.CreateOrderRequest;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Formula;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@ToString(callSuper = true)
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Table(name = "orders")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "order_type", discriminatorType = DiscriminatorType.STRING)
@DiscriminatorValue("SALE")
@com.fasterxml.jackson.annotation.JsonTypeInfo(
    use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,
    include = com.fasterxml.jackson.annotation.JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "orderType",
    visible = true,
    defaultImpl = Order.class
)
@com.fasterxml.jackson.annotation.JsonSubTypes({
    @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = Order.class, name = "SALE"),
    @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = com.restaurant.pos.purchasing.domain.PurchaseOrder.class, name = "PURCHASE")
})
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "order_no", nullable = false)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", length = 20, nullable = false, insertable = false, updatable = false)
    @Builder.Default
    private OrderType orderType = OrderType.SALE; // SALE, PURCHASE, EXPENSE

    @Column(name = "order_status", length = 20)
    @Builder.Default
    private String orderStatus = "DRAFT"; // DRAFT, CONFIRMED, COMPLETED, CANCELLED

    @Column(name = "doc_status", length = 20)
    @Builder.Default
    private String docStatus = "COMPLETED";

    @Column(name = "payment_status", length = 20)
    @Builder.Default
    private String paymentStatus = "PENDING"; // PENDING, PARTIAL, PAID

    @Column(name = "order_source", length = 50)
    @Builder.Default
    private String orderSource = "OFFLINE"; // OFFLINE, ONLINE, APP

    @Column(name = "terminal_id")
    private UUID terminalId;

    @Column(name = "source_device_id")
    private UUID sourceDeviceId;

    @Column(name = "source_terminal_id")
    private UUID sourceTerminalId;

    @Column(name = "source_operation_id", length = 160)
    private String sourceOperationId;

    @Column(name = "source_offline_id", length = 160)
    private String sourceOfflineId;

    @Column(name = "source_local_ref", length = 160)
    private String sourceLocalRef;

    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    @Column(name = "offline_created_at")
    private LocalDateTime offlineCreatedAt;

    @Column(name = "sync_origin", length = 40)
    private String syncOrigin;

    @Transient
    private String offlineInvoiceNo;

    @Transient
    private String offlinePaymentNo;

    @Transient
    private String roundOffMode;

    @Transient
    private BigDecimal lineDiscountFaceAmount;

    @Transient
    private BigDecimal lineDiscountBaseAmount;

    @Transient
    private BigDecimal orderDiscountFaceAmount;

    @Transient
    private BigDecimal orderDiscountBaseAmount;

    /** Transient: payment splits for direct-settle MIXED orders (not persisted). */
    @Transient
    private List<CreateOrderRequest.PaymentSplitRequest> paymentSplits;

    /** Transient: explicit amount paid (used when splits differ from grand total due to round-off). */
    @Transient
    private BigDecimal amountPaid;

    /** Formula: round-off loaded from the linked payment. Transient value is kept in memory for creation. */
    @Formula("(SELECT p.round_off_amount FROM payments p WHERE p.order_id = id ORDER BY p.created_at DESC LIMIT 1)")
    private BigDecimal roundOffAmount;

    @Column(name = "customer_id")
    private UUID customerId;

    @Builder.Default
    @JsonProperty("isCredit")
    @Column(name = "is_credit")
    private Boolean isCredit = false;

    @Column(name = "credit_customer_id")
    private UUID creditCustomerId;

    @Transient
    @JsonProperty("customerName")
    private String customerName;

    @Transient
    @JsonProperty("customerPhone")
    private String customerPhone;

    @Transient
    @JsonProperty("customerIds")
    private JsonNode customerIds;

    @Transient
    @Builder.Default
    @JsonProperty("customers")
    private List<OrderCustomerDto> customers = new ArrayList<>();

    @Transient
    @Builder.Default
    private List<String> skipAutoPrintKinds = new ArrayList<>();

    @Column(name = "vendor_id")
    private UUID vendorId;

    @Column(name = "pricelist_id")
    private UUID pricelistId;

    @Column(name = "currency_id")
    private UUID currencyId;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @Column(name = "order_date")
    @Builder.Default
    private Instant orderDate = Instant.now();

    @Builder.Default
    @Column(name = "total_tax_amount", precision = 15, scale = 2)
    private BigDecimal totalTaxAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_discount_amount", precision = 15, scale = 2)
    private BigDecimal totalDiscountAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "grand_total", precision = 15, scale = 2)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    // ─────────────────────────────────────────────────────────────
    // GST Discount Engine Fields (V1_110 migration)
    // NOTE: round_off_amount is intentionally NOT here — it lives
    // on Payment. The same order can be paid by UPI (no round-off)
    // or Cash (round-off). Order must stay neutral.
    // ─────────────────────────────────────────────────────────────

    /** Sum of gross_line_amount across all lines (pre-discount face total). */
    @Builder.Default
    @Column(name = "gross_amount", precision = 15, scale = 2)
    private BigDecimal grossAmount = BigDecimal.ZERO;

    /** PERCENT or AMOUNT — reconstructs what the user actually typed. */
    @Column(name = "order_discount_type", length = 10)
    private String orderDiscountType;

    /** The raw discount value entered by the user (e.g. 10 for 10%, or 100.00 for flat ₹100). */
    @Column(name = "order_discount_value", precision = 15, scale = 2)
    private BigDecimal orderDiscountValue;

    /** Originating source of this discount — MANUAL, QR, COUPON, PROMOTION, LOYALTY. */
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_source", length = 30)
    private DiscountSource discountSource;

    /** Discount calculation algorithm version stamped at order creation time. */
    @Column(name = "discount_calculation_version", length = 30)
    private String discountCalculationVersion;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String reference;

    @Column(name = "latitude", precision = 10, scale = 8)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 11, scale = 8)
    private BigDecimal longitude;

    @Column(name = "fulfillment_type", length = 20)
    @Builder.Default
    private String fulfillmentType = "DINE_IN"; // DINE_IN, TAKEAWAY, DELIVERY

    @Column(name = "table_number", length = 20)
    private String tableNumber;

    @Column(name = "table_id")
    private UUID tableId;

    @Column(name = "original_order_id")
    private UUID originalOrderId;

    @Column(name = "revision_number")
    @Builder.Default
    private Integer revisionNumber = 0;

    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private long version;

    @Formula("(SELECT i.invoice_no FROM invoices i WHERE i.order_id = id LIMIT 1)")
    private String invoiceNo;

    @Formula("(SELECT i.daily_bill_no FROM invoices i WHERE i.order_id = id LIMIT 1)")
    @JsonProperty("dailyBillNo")
    private Integer dailyBillNo;

    @Formula("(SELECT p.reference_no FROM payments p WHERE p.order_id = id ORDER BY p.created_at DESC LIMIT 1)")
    private String paymentNo;

    @Formula("(SELECT p.payment_method FROM payments p WHERE p.order_id = id ORDER BY p.created_at DESC LIMIT 1)")
    private String paymentMethod;

    @Builder.Default
    @JsonProperty("isActive")
    @Column(name = "isactive", length = 1)
    private String isactive = "Y";

    @org.hibernate.annotations.BatchSize(size = 50)
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OrderLine> lines = new ArrayList<>();

    public void addLine(OrderLine line) {
        lines.add(line);
        line.setOrder(this);
    }

    /**
     * Domain-safe check for the legacy 'Y'/'N' active flag.
     * Encapsulates the string comparison so it's never scattered across services.
     */
    public boolean isActive() {
        return "Y".equals(this.isactive);
    }

    /**
     * Marks this record as inactive in the legacy flag system.
     */
    public void deactivate() {
        this.isactive = "N";
    }
}
