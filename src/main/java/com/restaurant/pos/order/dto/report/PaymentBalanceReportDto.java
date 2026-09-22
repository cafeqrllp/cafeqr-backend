package com.restaurant.pos.order.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Top-level response DTO representing the comprehensive payment balances report
 * across all configured and active payment types.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentBalanceReportDto {

    private Instant from;
    private Instant to;
    private UUID orgId;
    private UUID terminalId;

    /** Aggregate money received across all payment methods */
    @Builder.Default
    private BigDecimal totalInflow = BigDecimal.ZERO;

    /** Aggregate money paid out across all payment methods */
    @Builder.Default
    private BigDecimal totalOutflow = BigDecimal.ZERO;

    /** Overall Net Balance = totalInflow - totalOutflow */
    @Builder.Default
    private BigDecimal netBalance = BigDecimal.ZERO;

    /** Total number of inbound transactions */
    @Builder.Default
    private long totalInflowCount = 0;

    /** Total number of outbound transactions */
    @Builder.Default
    private long totalOutflowCount = 0;

    /** List of individual balances per payment method */
    @Builder.Default
    private List<PaymentTypeBalanceDto> balances = new ArrayList<>();
}
