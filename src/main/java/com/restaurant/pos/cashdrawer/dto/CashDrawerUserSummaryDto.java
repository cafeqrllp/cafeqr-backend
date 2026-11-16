package com.restaurant.pos.cashdrawer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Per-user drawer open counts for a period; manual opens are the "No Sale" risk indicator. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashDrawerUserSummaryDto {
    private UUID userId;
    private String userName;
    private long total;
    private long saleSettle;
    private long orderSettle;
    private long manual;
    private long test;
    private long failed;
}
