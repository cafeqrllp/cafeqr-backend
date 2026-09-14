package com.restaurant.pos.pos.sale.dto;

import com.restaurant.pos.pos.sale.query.PosProductSummaryView;
import java.util.List;

/**
 * Lightweight keyset-paginated product catalog response.
 * Returns only the requested page of products and an opaque nextCursor.
 */
public record PosProductPageResponse(
        List<PosProductSummaryView> items,
        String nextCursor,
        boolean hasMore
) {
}
