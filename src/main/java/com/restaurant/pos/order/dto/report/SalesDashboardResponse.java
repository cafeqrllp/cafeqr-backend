package com.restaurant.pos.order.dto.report;

import com.restaurant.pos.order.dto.OrderSummaryDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesDashboardResponse {
    private SalesSummaryDto summary;
    private PageResponse<OrderSummaryDto> orders;
}
