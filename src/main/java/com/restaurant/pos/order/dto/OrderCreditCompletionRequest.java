package com.restaurant.pos.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class OrderCreditCompletionRequest {
    private UUID creditCustomerId;
    private BigDecimal discountAmount;
    private BigDecimal roundOffAmount;
    private String roundOffMode;
    private String description;
    private List<String> skipAutoPrintKinds;
}
