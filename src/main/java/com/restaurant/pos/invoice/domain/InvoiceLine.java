package com.restaurant.pos.invoice.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.restaurant.pos.order.domain.TaxType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@ToString(exclude = "invoice")
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "invoice")
@Table(name = "invoice_lines")
public class InvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    @JsonIgnore
    private Invoice invoice;

    @Column(name = "order_line_id")
    private UUID orderLineId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "variant_id")
    private UUID variantId;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "category_name")
    private String categoryName;

    @Column(name = "is_packaged_good")
    private Boolean isPackagedGood;

    @Builder.Default
    @Column(precision = 15, scale = 3, nullable = false)
    private BigDecimal quantity = BigDecimal.ONE;

    @Builder.Default
    @Column(name = "unit_of_measure", length = 20)
    private String unitOfMeasure = "units";

    @Builder.Default
    @Column(name = "uom_precision")
    private Integer uomPrecision = 0;

    @Column(name = "unit_price", precision = 15, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    @Builder.Default
    @Column(name = "tax_rate", precision = 5, scale = 2)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "tax_amount", precision = 15, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "discount_amount", precision = 15, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "line_total", precision = 15, scale = 2, nullable = false)
    private BigDecimal lineTotal;

    // ─────────────────────────────────────────────────────────────
    // GST Enrichment Fields (V1_110 migration)
    // IMPORTANT: These are immutable snapshots copied from OrderLine
    // at invoice generation time. They MUST be reused as-is for any
    // future credit note / reversal — never re-derived from master tables.
    // ─────────────────────────────────────────────────────────────

    /** qty × unit_price (face/MRP) before any discount. */
    @Column(name = "gross_line_amount", precision = 15, scale = 2)
    private BigDecimal grossLineAmount;

    /** MRP ÷ (1 + rate/100) for INCLUSIVE lines; equals unit_price for EXCLUSIVE. */
    @Column(name = "unit_price_ex_tax", precision = 15, scale = 4)
    private BigDecimal unitPriceExTax;

    /** Taxable base after ALL discounts (line-level + allocated order discount). */
    @Column(name = "taxable_amount", precision = 15, scale = 2)
    private BigDecimal taxableAmount;

    /** Whether tax is included in unit_price or added on top. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tax_type", length = 10)
    private TaxType taxType;

    /** Snapshot of the actual GST rate applied at invoice time (e.g. 18.0000). Never re-read from master tables. */
    @Column(name = "tax_snapshot_rate", precision = 7, scale = 4)
    private BigDecimal taxSnapshotRate;

    /** Tax code at invoice time — e.g. GST_5, GST_12, GST_18, GST_28. */
    @Column(name = "tax_code", length = 30)
    private String taxCode;

    /** Human-readable tax label at invoice time — e.g. "GST 18%". Not a FK. */
    @Column(name = "tax_name", length = 100)
    private String taxName;

    /** User-entered flat line discount (face value). Null if discount was % based. */
    @Column(name = "manual_discount_amount", precision = 15, scale = 2)
    private BigDecimal manualDiscountAmount;

    /** User-entered % line discount (snapshot of input). Null if discount was flat amount. */
    @Column(name = "manual_discount_percent", precision = 7, scale = 4)
    private BigDecimal manualDiscountPercent;

    /** Order-level discount proportionally allocated to this line (base amount). */
    @Column(name = "allocated_order_discount", precision = 15, scale = 2)
    private BigDecimal allocatedOrderDiscount;

    @Builder.Default
    @JsonProperty("isActive")
    @Column(name = "isactive", length = 1)
    private String isactive = "Y";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_by")
    private String updatedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        try {
            UUID userId = com.restaurant.pos.common.util.SecurityUtils.getCurrentUserId();
            if (userId != null) {
                if (this.createdBy == null) {
                    this.createdBy = userId.toString();
                }
                if (this.updatedBy == null) {
                    this.updatedBy = userId.toString();
                }
            }
        } catch (Exception ignored) {}
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        try {
            UUID userId = com.restaurant.pos.common.util.SecurityUtils.getCurrentUserId();
            if (userId != null) {
                this.updatedBy = userId.toString();
            }
        } catch (Exception ignored) {}
    }

    public boolean isActive() {
        return "Y".equals(this.isactive);
    }

    public void deactivate() {
        this.isactive = "N";
    }
}
