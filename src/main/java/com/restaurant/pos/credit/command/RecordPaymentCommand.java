package com.restaurant.pos.credit.command;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * CQRS command for recording a credit payment.
 */
@Data
@Builder
public class RecordPaymentCommand {
    private UUID creditCustomerId;
    private UUID orgId;
    private BigDecimal amount;
    private String paymentMethod;
    private String description;
    private String allocationMode;
    private UUID invoiceId;
    private String partnerType;
    private String idempotencyKey;
    private List<AllocationEntry> allocations;

    @Data
    @Builder
    public static class AllocationEntry {
        private UUID invoiceId;
        private BigDecimal amount;
    }
}
