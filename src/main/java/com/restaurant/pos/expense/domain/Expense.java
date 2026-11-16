package com.restaurant.pos.expense.domain;

import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Enterprise-grade Domain Entity for Expenses.
 * Dedicated to the stand-alone 'expenses' table.
 */
@Getter
@Setter
@ToString(callSuper = true)
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Table(name = "expenses")
public class Expense extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "expense_no", nullable = false)
    private String expenseNo;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "terminal_id")
    private UUID terminalId;

    @Column(name = "expense_date", nullable = false)
    @Builder.Default
    private Instant expenseDate = Instant.now();

    @Builder.Default
    @Column(name = "amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "doc_status", length = 20)
    @Builder.Default
    private String docStatus = "COMPLETED";

    @Column(name = "payment_status", length = 20)
    @Builder.Default
    private String paymentStatus = "PAID";

    @Column(name = "original_expense_id")
    private UUID originalExpenseId;

    @Column(name = "revision_number")
    @Builder.Default
    private Integer revisionNumber = 0;

    @Column(name = "currency_id")
    private UUID currencyId;

    // ── India GST details (optional; V1_204). `amount` stays the tax-inclusive total paid. ──
    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "supplier_gstin", length = 20)
    private String supplierGstin;

    @Column(name = "bill_no", length = 100)
    private String billNo;

    @Column(name = "hsn_sac", length = 20)
    private String hsnSac;

    @Column(name = "gst_rate", precision = 7, scale = 4)
    private BigDecimal gstRate;

    @Column(name = "tax_amount", precision = 15, scale = 2)
    private BigDecimal taxAmount;

    public static final String ACTIVE_FLAG = "Y";
    public static final String INACTIVE_FLAG = "N";

    @Builder.Default
    @Column(name = "isactive", length = 1)
    private String activeFlag = ACTIVE_FLAG;

    public boolean isActive() {
        return ACTIVE_FLAG.equals(this.activeFlag);
    }

    public void deactivate() {
        this.activeFlag = INACTIVE_FLAG;
    }

    public String getScope() {
        return this.getOrgId() == null 
                ? ScopeType.GLOBAL.name() 
                : ScopeType.BRANCH.name();
    }
}
