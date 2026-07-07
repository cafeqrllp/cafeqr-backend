package com.restaurant.pos.order.dto;

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
public class OrderPaymentDto {
    private UUID paymentId;
    private String referenceNo;
    private LocalDateTime paymentDate;
    private BigDecimal amount;
    private String paymentMethod;
    private String description;
}
