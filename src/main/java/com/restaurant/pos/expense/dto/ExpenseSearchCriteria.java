package com.restaurant.pos.expense.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Enterprise-grade Search Criteria for Expense filtering.
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Criteria for filtering expense records")
public class ExpenseSearchCriteria {

    @Schema(description = "Starting timestamp for the search range")
    private Instant fromDate;

    @Schema(description = "Ending timestamp for the search range")
    private Instant toDate;

    @Schema(description = "Filter by specific category ID")
    private UUID categoryId;

    @Schema(description = "Filter by specific payment channel recorded in the reference field", example = "CASH")
    private String paymentMethod;

    @Schema(description = "Fuzzy search term matching reference number or description", example = "Bill")
    private String searchTerm;

    @Schema(description = "Filter results by organizational branch ID")
    private UUID branchId;

    @Schema(description = "Scope filter: ALL, GLOBAL, or BRANCH", example = "ALL")
    private String scope;

    @Schema(description = "Filter results by status (ACTIVE/VOID)", example = "ACTIVE")
    private String status;

    public void setFrom(Instant from) {
        if (from != null) {
            this.fromDate = from;
        }
    }

    public void setTo(Instant to) {
        if (to != null) {
            this.toDate = to;
        }
    }

    public void setOrgId(UUID orgId) {
        if (orgId != null) {
            this.branchId = orgId;
        }
    }
}
