package com.restaurant.pos.invoice.domain;

import com.restaurant.pos.common.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table(name = "invoices")
public class Invoice extends BaseEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_type", length = 30)
    private InvoiceType invoiceType; // CUSTOMER_INVOICE, VENDOR_BILL, EXPENSE_RECEIPT



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

    @Column(name = "offline_created_at")
    private LocalDateTime offlineCreatedAt;

    @Column(name = "sync_origin", length = 40)
    private String syncOrigin;

    @Column(name = "order_id", unique = true)
    private UUID orderId;

    @Column(name = "expense_id")
    private UUID expenseId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "credit_customer_id")
    private UUID creditCustomerId;

    @Column(name = "vendor_id")
    private UUID vendorId;

    @Column(name = "invoice_no", nullable = false)
    private String invoiceNo;

    @Column(name = "invoice_date")
    @Builder.Default
    private LocalDateTime invoiceDate = LocalDateTime.now();

    @Column(name = "due_date")
    private LocalDateTime dueDate;

    @Builder.Default
    @Column(length = 20)
    private String status = "UNPAID"; // UNPAID, PARTIAL, PAID, VOID

    @Column(name = "doc_status", length = 20)
    @Builder.Default
    private String docStatus = "COMPLETED";

    @Builder.Default
    @Column(name = "is_paid")
    private Boolean isPaid = false;

    @Column(name = "is_credit")
    @Builder.Default
    private Boolean isCredit = false;

    @Column(name = "original_invoice_id")
    private UUID originalInvoiceId;



    @Column(name = "total_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "amount_due", precision = 15, scale = 2, nullable = false)
    private BigDecimal amountDue;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String reference;

    @Builder.Default
    @JsonProperty("isActive")
    @Column(name = "isactive", length = 1)
    private String isactive = "Y";

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private java.util.List<InvoiceLine> lines = new java.util.ArrayList<>();

    public void addLine(InvoiceLine line) {
        lines.add(line);
        line.setInvoice(this);
    }
}
