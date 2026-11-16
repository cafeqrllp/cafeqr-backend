package com.restaurant.pos.cashdrawer.dto;

import com.restaurant.pos.cashdrawer.domain.CashDrawerEventStatus;
import com.restaurant.pos.cashdrawer.domain.CashDrawerTrigger;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashDrawerEventDto {
    private UUID id;
    private UUID orgId;
    private UUID terminalId;
    private String terminalName;
    private UUID userId;
    private String userName;
    private String roleName;
    private CashDrawerTrigger triggerType;
    private String reason;
    private UUID orderId;
    private String orderNo;
    private String paymentMethod;
    private BigDecimal amount;
    private String printerName;
    private String transport;
    private CashDrawerEventStatus status;
    private String errorMessage;
    private String deviceLabel;
    /** NOT_PERMITTED / REASON_MISSING when the open broke a rule; null otherwise. */
    private String policyViolation;
    /** Server time (UTC) the event was recorded. */
    private LocalDateTime recordedAt;
    /** Device time (UTC) the kick was attempted. */
    private LocalDateTime deviceEventAt;
}
