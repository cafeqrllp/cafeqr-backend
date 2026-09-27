package com.restaurant.pos.warehouse.api;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.security.StaffAccess;
import com.restaurant.pos.subscription.annotation.RequireModule;
import com.restaurant.pos.subscription.domain.ModuleName;
import com.restaurant.pos.warehouse.domain.Warehouse;
import com.restaurant.pos.warehouse.query.WarehouseQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * CQRS Query Controller for Warehouse operations.
 * Handles read queries: LIST, GET BY ID.
 */
@Slf4j
@StaffAccess
@RestController
@RequestMapping("/api/v1/warehouses")
@RequiredArgsConstructor
@Validated
@RequireModule(ModuleName.INVENTORY)
@Tag(name = "Warehouse Queries", description = "Endpoints for querying Warehouses.")
public class WarehouseQueryController {

    private final WarehouseQueryService queryService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(
        summary = "List Warehouses",
        description = "Returns all warehouses visible to the current tenant/branch. Super-admins see all client warehouses when no orgId is provided. Set all=true to list all warehouses for cross-branch stock transfers."
    )
    public ResponseEntity<ApiResponse<List<Warehouse>>> getWarehouses(
            @Parameter(description = "Filter by branch (orgId). Omit to use the branch from the security context.")
            @RequestParam(required = false) UUID orgId,
            @Parameter(description = "If true, returns all client warehouses across all branches (useful for stock transfers).")
            @RequestParam(required = false, defaultValue = "false") boolean all) {

        log.info("Listing warehouses | orgId={}, all={}", orgId, all);
        return ResponseEntity.ok(ApiResponse.success(queryService.getWarehouses(orgId, all)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(
        summary = "Get Warehouse by ID",
        description = "Returns a single Warehouse record. Scoped to the current tenant and branch."
    )
    public ResponseEntity<ApiResponse<Warehouse>> getWarehouse(
            @Parameter(description = "UUID of the Warehouse", required = true) @PathVariable UUID id) {

        log.info("Fetching warehouse | id={}", id);
        return ResponseEntity.ok(ApiResponse.success(queryService.getWarehouse(id)));
    }
}
