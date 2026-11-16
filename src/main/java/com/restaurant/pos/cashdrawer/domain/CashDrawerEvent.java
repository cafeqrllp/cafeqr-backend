package com.restaurant.pos.cashdrawer.domain;

import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Append-only record of every cash drawer kick (automatic or manual).
 * Rows are never updated or deleted through the application.
 */
@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "cash_drawer_events")
public class CashDrawerEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "terminal_id")
    private UUID terminalId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "role_name", length = 100)
    private String roleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", length = 30, nullable = false)
    private CashDrawerTrigger triggerType;

    @Column(name = "reason")
    private String reason;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "order_no", length = 100)
    private String orderNo;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "printer_name")
    private String printerName;

    @Column(name = "transport", length = 50)
    private String transport;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private CashDrawerEventStatus status;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** Clock of the device that sent the kick (UTC). Differs from created_at for offline replays. */
    @Column(name = "device_event_at")
    private LocalDateTime deviceEventAt;

    /** Device-generated id; makes offline retries idempotent. */
    @Column(name = "client_event_id", length = 100)
    private String clientEventId;

    @Column(name = "device_label")
    private String deviceLabel;

    /**
     * Set when the open broke a rule (NOT_PERMITTED, REASON_MISSING). The device kicks the drawer before
     * the server sees the event, so such opens are recorded and flagged rather than rejected.
     */
    @Column(name = "policy_violation", length = 40)
    private String policyViolation;
}
