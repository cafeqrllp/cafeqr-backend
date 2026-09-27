package com.restaurant.pos.purchase.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Vendor-wise purchase spend and ledger summary DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Aggregated vendor procurement and payment ledger statistics")
public class PurchaseVendorReportDto {

    @Schema(description = "Vendor UUID")
    private UUID vendorId;

    @Schema(description = "Vendor / Supplier Name")
    private String vendorName;

    @Schema(description = "Total number of purchase orders with this vendor")
    @Builder.Default
    private long orderCount = 0;

    @Schema(description = "Total billed amount across purchase orders")
    @Builder.Default
    private BigDecimal totalBilled = BigDecimal.ZERO;

    @Schema(description = "Total paid amount to this vendor")
    @Builder.Default
    private BigDecimal totalPaid = BigDecimal.ZERO;

    @Schema(description = "Outstanding balance / payables owed to this vendor")
    @Builder.Default
    private BigDecimal outstandingBalance = BigDecimal.ZERO;

    @Schema(description = "Timestamp of the most recent order with this vendor")
    private Instant lastOrderDate;
}
