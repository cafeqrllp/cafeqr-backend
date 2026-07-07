package com.restaurant.pos.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Read-only projection of a payment split record")
public record PaymentSplitResponseDto(

        @Schema(description = "Unique identifier of the payment split")
        UUID id,

        @Schema(description = "Payment method used for this split (CASH, ONLINE, UPI, CARD, BANK, CHEQUE)")
        String paymentMethod,

        @Schema(description = "Amount allocated to this split")
        BigDecimal amount,

        @Schema(description = "Optional transaction or gateway reference number")
        String referenceNo
) {}
