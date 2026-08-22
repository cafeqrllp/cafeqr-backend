package com.restaurant.pos.credit.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class CreditOrderDto {
    private UUID orderId;
    private UUID invoiceId;
    private String orderNo;
    private String invoiceNo;
    private String customerName;
    private String customerPhone;
    private BigDecimal amount;
    private BigDecimal tax;
    private BigDecimal total;
    private BigDecimal amountPaid;
    private BigDecimal amountDue;
    private LocalDateTime date;
    private String status;
    private String paymentStatus;
}
