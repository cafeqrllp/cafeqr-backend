package com.restaurant.pos.order.domain;

import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@ToString(callSuper = true)
@Entity
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Table(name = "payments")
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", length = 20)
    private PaymentType paymentType; // INBOUND (Sales) or OUTBOUND (Purchase/Expense)



    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "org_id")
    private UUID orgId;

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

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "credit_customer_id")
    private UUID creditCustomerId;

    @Column(name = "expense_id")
    private UUID expenseId;



    @Column(name = "payment_date")
    @Builder.Default
    private LocalDateTime paymentDate = LocalDateTime.now();

    @Column(name = "payment_method", length = 50, nullable = false)
    private String paymentMethod;

    @Column(name = "amount_paid", precision = 15, scale = 2, nullable = false)
    private BigDecimal amountPaid;

    @Column(name = "change_given", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal changeGiven = BigDecimal.ZERO;

    @Column(name = "reference_no", length = 100)
    private String referenceNo;

    @Column(columnDefinition = "TEXT")
    private String description;



    @Column(name = "doc_status", length = 20)
    @Builder.Default
    private String docStatus = "COMPLETED";

    @Builder.Default
    @Column(name = "isactive", length = 1)
    private String isactive = "Y";
}
