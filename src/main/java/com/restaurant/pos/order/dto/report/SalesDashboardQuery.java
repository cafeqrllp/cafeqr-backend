package com.restaurant.pos.order.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesDashboardQuery {
    @NotNull
    private Instant from;

    @NotNull
    private Instant to;

    @Size(max = 100)
    private String q;

    @jakarta.validation.constraints.Pattern(
        regexp = "^(?i)(PAID|COMPLETED|BILLED|CANCELLED|VOID|DRAFT|COMPLETED_CANCELLED)$",
        message = "Invalid sales status"
    )
    private String status;

    private UUID orgId;

    private UUID terminalId;

    @Min(0)
    private Integer page;

    @Min(1)
    @Max(100)
    private Integer size;

    public int getEffectivePage() {
        return page == null ? 0 : page;
    }

    public int getEffectiveSize() {
        return size == null ? 20 : size;
    }
}
