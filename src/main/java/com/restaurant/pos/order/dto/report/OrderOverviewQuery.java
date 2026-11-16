package com.restaurant.pos.order.dto.report;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Filters for the Business Overview screen. Bound from query parameters, so the
 * endpoint stays a cacheable GET.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderOverviewQuery {

    @Pattern(regexp = "^(?i)(SALE|PURCHASE)$", message = "type must be SALE or PURCHASE")
    private String type;

    private Instant from;

    private Instant to;

    /** IANA zone of the branch, used to bucket hours/days in branch wall-clock time. */
    @Size(max = 64)
    private String tz;

    /** Super admin only; ignored for other roles (they are pinned to their own branch). */
    private UUID orgId;

    private UUID terminalId;

    // ── list-only parameters ──

    @Pattern(regexp = "^(?i)(ALL|ORDERED|BILLED|COMPLETED|CANCELLED)$", message = "Invalid status group")
    private String status;

    @Size(max = 100)
    private String q;

    @Pattern(regexp = "^(date|orderNo|status|createdBy|updatedBy|amount)$", message = "Invalid sort key")
    private String sort;

    @Pattern(regexp = "^(?i)(asc|desc)$", message = "dir must be asc or desc")
    private String dir;

    @Min(0)
    private Integer page;

    @Min(1)
    @Max(200)
    private Integer size;

    public int getEffectivePage() {
        return page == null ? 0 : page;
    }

    public int getEffectiveSize() {
        return size == null ? 50 : size;
    }
}
