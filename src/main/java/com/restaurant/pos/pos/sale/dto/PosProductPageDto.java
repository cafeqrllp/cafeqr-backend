package com.restaurant.pos.pos.sale.dto;

import com.restaurant.pos.pos.sale.query.PosProductSummaryDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Cacheable container for keyset paginated products page in Redis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PosProductPageDto {
    private List<PosProductSummaryDto> items;
    private String nextCursor;
    private boolean hasMore;
}
