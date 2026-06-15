package com.restaurant.pos.credit.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class CreditPaymentRequest {
    private UUID orgId;
    private BigDecimal amount;
    private String paymentMethod;
    private String description;
    private String allocationMode;
    private UUID invoiceId;
    private List<AllocationRequest> allocations;

    @Data
    public static class AllocationRequest {
        private UUID invoiceId;
        private BigDecimal amount;
    }
}
