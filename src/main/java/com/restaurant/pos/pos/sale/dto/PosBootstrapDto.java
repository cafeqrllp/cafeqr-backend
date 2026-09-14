package com.restaurant.pos.pos.sale.dto;

import com.restaurant.pos.common.dto.ConfigurationDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated POS bootstrap payload returned by {@code GET /api/v1/pos/sale/bootstrap}.
 * <p>
 * Bundles configurations, payment methods, pricelists, categories, and the full
 * product catalog into a single HTTP response — replacing the 5–7 waterfall
 * requests that the current CounterSale bootstrap fires on mount.
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PosBootstrapDto {

    /** Server clock at response time — used by client for delta sync. */
    private Instant serverTimestamp;

    /** Full POS configuration for the current branch. */
    private ConfigurationDto configurations;

    /** Active sale pricelists. */
    private List<Object> pricelists;

    /** All product categories (active). */
    private List<String> categories;

    /** Active products for the POS catalog. */
    private List<Object> products;

    /** Active purchasing customers. */
    private List<Object> customers;

    /** Active credit customers. */
    private List<Object> creditCustomers;
}
