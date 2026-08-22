package com.restaurant.pos.credit.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class CreditPaymentRequest {
    private UUID orgId;

    @NotNull(message = "Payment amount is required")
    @DecimalMin(value = "0.01", message = "Payment amount must be greater than zero")
    @Digits(integer = 12, fraction = 2, message = "Payment amount scale cannot exceed 2 decimal places")
    private BigDecimal amount;

    @NotBlank(message = "Payment method is required")
    @Size(max = 50, message = "Payment method must not exceed 50 characters")
    private String paymentMethod;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;

    @Size(max = 30, message = "Allocation mode must not exceed 30 characters")
    private String allocationMode;

    private UUID invoiceId;

    @Size(max = 20, message = "Partner type must not exceed 20 characters")
    private String partnerType;

    @Valid
    @Size(max = 200, message = "Cannot exceed 200 manual allocations per request")
    private List<AllocationRequest> allocations;

    @Data
    public static class AllocationRequest {
        @NotNull(message = "Invoice ID is required for allocation")
        private UUID invoiceId;

        @NotNull(message = "Allocation amount is required")
        @DecimalMin(value = "0.01", message = "Allocation amount must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Allocation amount scale cannot exceed 2 decimal places")
        private BigDecimal amount;
    }
}

