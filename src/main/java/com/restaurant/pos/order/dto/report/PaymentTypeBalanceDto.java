package com.restaurant.pos.order.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Enterprise DTO representing the financial movement and net balance
 * for a specific payment method (Cash, Online, Credit, Mixed, etc.) over a time period.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentTypeBalanceDto {

    /** Normalized identifier (e.g. "CASH", "ONLINE", "CREDIT") */
    private String paymentMethod;

    /** Human-readable display name from Payment Types module (e.g. "Cash", "UPI — GPay", "HDFC Card") */
    private String displayName;

    /** Configured category from payment_types table (e.g. "OTHERS", "CREDIT") */
    private String category;

    /** Whether this payment type is configured in the Payment Types module */
    private boolean isConfigured;

    /** Sort order from Payment Types configuration */
    private Integer sortOrder;

    /** Total money collected (Sales + Credit Settlements / Collections) */
    @Builder.Default
    private BigDecimal inflowAmount = BigDecimal.ZERO;

    /** Number of inbound transactions */
    @Builder.Default
    private long inflowCount = 0;

    /** Portion of inflows from sales orders */
    @Builder.Default
    private BigDecimal salesAmount = BigDecimal.ZERO;

    /** Portion of inflows from customer credit settlements / debt receipts */
    @Builder.Default
    private BigDecimal collectionAmount = BigDecimal.ZERO;

    /** Total money paid out (Expenses + Vendor Purchases) */
    @Builder.Default
    private BigDecimal outflowAmount = BigDecimal.ZERO;

    /** Number of outbound transactions */
    @Builder.Default
    private long outflowCount = 0;

    /** Portion of outflows from operating expenses */
    @Builder.Default
    private BigDecimal expenseAmount = BigDecimal.ZERO;

    /** Portion of outflows from vendor purchases / bills */
    @Builder.Default
    private BigDecimal purchaseAmount = BigDecimal.ZERO;

    /** Net Period Balance = Inflow - Outflow */
    @Builder.Default
    private BigDecimal netBalance = BigDecimal.ZERO;

    /** Financial status: "SURPLUS", "DEFICIT", or "SETTLED" */
    private String status;

    /** Percentage of total inflows */
    @Builder.Default
    private BigDecimal percentage = BigDecimal.ZERO;

    /** Average value per inbound transaction */
    @Builder.Default
    private BigDecimal averageTransaction = BigDecimal.ZERO;
}
