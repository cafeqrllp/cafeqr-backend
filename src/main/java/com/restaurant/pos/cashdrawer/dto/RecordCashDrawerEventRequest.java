package com.restaurant.pos.cashdrawer.dto;

import com.restaurant.pos.cashdrawer.domain.CashDrawerEventStatus;
import com.restaurant.pos.cashdrawer.domain.CashDrawerTrigger;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class RecordCashDrawerEventRequest {

    @NotNull
    private CashDrawerTrigger triggerType;

    @NotNull
    private CashDrawerEventStatus status;

    @Size(max = 255)
    private String reason;

    private UUID orderId;

    @Size(max = 100)
    private String orderNo;

    @Size(max = 50)
    private String paymentMethod;

    private BigDecimal amount;

    @Size(max = 255)
    private String printerName;

    @Size(max = 50)
    private String transport;

    @Size(max = 500)
    private String errorMessage;

    /** Device clock in UTC at the moment the kick was attempted. */
    private LocalDateTime deviceEventAt;

    /** Device-generated unique id, used to make offline replays idempotent. */
    @Size(max = 100)
    private String clientEventId;

    @Size(max = 255)
    private String deviceLabel;
}
