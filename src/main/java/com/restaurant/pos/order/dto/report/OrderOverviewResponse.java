package com.restaurant.pos.order.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Pre-aggregated Business Overview payload. Size is bounded (a few KB) no matter how
 * many orders fall inside the range, so the screen stays fast for any branch size.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderOverviewResponse {

    /** Orders in range excluding cancelled. */
    private long orderCount;
    /** All non-void orders in range, including cancelled (donut total). */
    private long totalCount;
    private BigDecimal revenue;
    private BigDecimal avgOrderValue;

    /** ORDERED / BILLED / COMPLETED / CANCELLED → count. */
    private Map<String, Long> byStatus;

    private List<Bucket> byFulfillment;
    private List<Bucket> byPayment;
    /** Top 10 by amount; key = warehouse id. */
    private List<Bucket> byWarehouse;
    /** Top 10 by amount; key = vendor id. */
    private List<Bucket> byVendor;

    /** 24 entries, hour-of-day in branch time. */
    private List<Bucket> hourly;

    /** HOUR or DAY. */
    private String trendGranularity;
    /** Sparse; key = yyyy-MM-ddTHH (HOUR) or yyyy-MM-dd (DAY) in branch time. */
    private List<Bucket> trend;

    /** Top 10 products by quantity. */
    private List<ItemBucket> topItems;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bucket {
        private String key;
        private long count;
        private BigDecimal total;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemBucket {
        private String name;
        private BigDecimal quantity;
        private BigDecimal total;
        /** Number of order lines for this product in range (not distinct orders). */
        private long orderCount;
        private int uomPrecision;
    }
}
