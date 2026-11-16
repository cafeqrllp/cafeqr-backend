package com.restaurant.pos.order.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Lightweight row for the Overview table; the full order is fetched only when a row is opened. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderOverviewRowDto {
    private UUID id;
    private String orderNo;
    /** Status group: ORDERED / BILLED / COMPLETED / CANCELLED. */
    private String status;
    private String originalStatus;
    private String createdBy;
    private String updatedBy;
    private BigDecimal totalAmount;
    private Instant orderDate;
}
