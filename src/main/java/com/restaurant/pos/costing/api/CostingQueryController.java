package com.restaurant.pos.costing.api;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.security.StaffAccess;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.costing.dto.CostDetailReportDto;
import com.restaurant.pos.costing.dto.ProductCostDto;
import com.restaurant.pos.costing.query.CostDetailReportService;
import com.restaurant.pos.costing.query.CostingQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Read-only inventory costing endpoints: current cost of a product in the current branch.
 */
@StaffAccess
@RestController
@RequestMapping("/api/v1/costing")
@RequiredArgsConstructor
@Tag(name = "Inventory Costing", description = "Current stock cost per product (weighted average / last PO / standard).")
public class CostingQueryController {

    private final CostingQueryService costingQueryService;
    private final CostDetailReportService costDetailReportService;

    @GetMapping("/products/{productId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Get product cost", description = "Current cost records (base product and variants) for the current branch.")
    public ResponseEntity<ApiResponse<List<ProductCostDto>>> getProductCost(
            @PathVariable UUID productId,
            @RequestParam(value = "orgId", required = false) UUID orgId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID effectiveOrgId = orgId != null ? orgId : TenantContext.getCurrentOrg();
        return ResponseEntity.ok(ApiResponse.success(
                costingQueryService.productCosts(clientId, effectiveOrgId, productId)));
    }

    @GetMapping("/products/{productId}/cost-detail")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    @Operation(summary = "Product cost report",
            description = "Every costed stock movement of a product in a period for the current branch: "
                    + "unit cost, line cost and the product cost before/after each movement.")
    public ResponseEntity<ApiResponse<CostDetailReportDto>> getCostDetail(
            @PathVariable UUID productId,
            @RequestParam(value = "variantId", required = false) UUID variantId,
            @RequestParam(value = "warehouseId", required = false) UUID warehouseId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "orgId", required = false) UUID orgId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID effectiveOrgId = orgId != null ? orgId : TenantContext.getCurrentOrg();
        if (effectiveOrgId == null) {
            throw new BusinessException("Select a branch to view the product cost report");
        }
        return ResponseEntity.ok(ApiResponse.success(costDetailReportService.report(
                clientId, effectiveOrgId, productId, variantId, warehouseId, parseUtc(from), parseUtc(to))));
    }

    /** Stock ledger timestamps are stored in UTC; accept an ISO instant/offset or a plain UTC local date-time. */
    private static LocalDateTime parseUtc(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            return LocalDateTime.ofInstant(Instant.parse(v), ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(v).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException ex) {
            throw new BusinessException("Invalid date-time: " + value);
        }
    }
}
