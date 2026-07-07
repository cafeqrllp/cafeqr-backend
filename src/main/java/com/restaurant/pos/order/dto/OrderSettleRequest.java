package com.restaurant.pos.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "Request payload for settling an order and recording corresponding payments")
public class OrderSettleRequest {

    @Schema(description = "Primary payment method (e.g. 'CASH', 'ONLINE', 'CARD', 'UPI', 'CREDIT', 'MIXED')", example = "CASH", requiredMode = Schema.RequiredMode.REQUIRED)
    private String paymentMethod;

    @Schema(description = "Amount paid in cash (useful for mixed payments)", example = "50.00")
    private BigDecimal cashAmount;

    @Schema(description = "Amount paid online/digitally (useful for mixed payments)", example = "38.00")
    private BigDecimal onlineAmount;

    @Schema(description = "Total financial amount paid by the customer/vendor", example = "88.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amountPaid;

    @Schema(description = "Discount amount applied to this settlement", example = "10.00")
    private BigDecimal discountAmount;

    @Schema(description = "Round-off adjustment applied to this settlement", example = "0.00")
    private BigDecimal roundOffAmount;

    @Schema(description = "Round-off mode (e.g. AUTOMATIC, MANUAL, DISABLED)", example = "AUTOMATIC")
    private String roundOffMode;

    @Schema(description = "Operational description or notes for this payment", example = "Settled with discount")
    private String description;

    @Schema(description = "Details of multi-method split payments if primary method is MIXED")
    private List<PaymentSplitRequest> paymentSplits;

    @Schema(description = "Transient print kinds that this terminal will print locally, e.g. BILL")
    private List<String> skipAutoPrintKinds;

    @Data
    @Schema(description = "Split payment item details")
    public static class PaymentSplitRequest {

        @Schema(description = "Payment method for this split (CASH, ONLINE, UPI, CARD, BANK, CHEQUE)", example = "CASH", requiredMode = Schema.RequiredMode.REQUIRED)
        private String paymentMethod;

        @Schema(description = "Payment amount for this split segment", example = "50.00", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal amount;

        @Schema(description = "Optional transaction or gateway reference number", example = "TXN12345678")
        private String referenceNo;
    }
}
