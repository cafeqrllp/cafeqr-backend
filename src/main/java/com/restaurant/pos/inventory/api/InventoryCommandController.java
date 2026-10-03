package com.restaurant.pos.inventory.api;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.security.StaffAccess;
import com.restaurant.pos.inventory.command.InventoryCommandService;
import com.restaurant.pos.inventory.domain.StockAdjustment;
import com.restaurant.pos.inventory.domain.StockTransfer;
import com.restaurant.pos.subscription.annotation.RequireModule;
import com.restaurant.pos.subscription.domain.ModuleName;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * CQRS Command Controller for Inventory operations.
 * Handles state mutation commands (CREATE/UPDATE ADJUSTMENT, CREATE/UPDATE TRANSFER).
 */
@Slf4j
@StaffAccess
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
@Validated
@RequireModule(ModuleName.INVENTORY)
@Tag(name = "Inventory Commands", description = "Endpoints for creating and updating inventory adjustments and transfers.")
public class InventoryCommandController {

    private final InventoryCommandService commandService;

    @PostMapping("/adjustments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    @Operation(summary = "Create Stock Adjustment", description = "Creates a new stock adjustment document and updates inventory balances if completed.")
    public ResponseEntity<ApiResponse<StockAdjustment>> createAdjustment(@RequestBody StockAdjustment adjustment) {
        return ResponseEntity.ok(ApiResponse.success(commandService.saveAdjustment(adjustment)));
    }

    @PutMapping("/adjustments/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    @Operation(summary = "Update Stock Adjustment", description = "Updates an existing stock adjustment record.")
    public ResponseEntity<ApiResponse<StockAdjustment>> updateAdjustment(@PathVariable UUID id, @RequestBody StockAdjustment adjustment) {
        adjustment.setId(id);
        return ResponseEntity.ok(ApiResponse.success(commandService.saveAdjustment(adjustment)));
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    @Operation(summary = "Create Stock Transfer", description = "Creates a new stock transfer between warehouses and executes inventory movement if completed.")
    public ResponseEntity<ApiResponse<StockTransfer>> createTransfer(@RequestBody StockTransfer transfer) {
        StockTransfer saved = commandService.saveTransfer(transfer);
        if (saved != null && saved.getWarnings() != null && !saved.getWarnings().isEmpty()) {
            return ResponseEntity.ok(ApiResponse.successWithWarnings(saved, saved.getWarnings()));
        }
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @PutMapping("/transfers/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    @Operation(summary = "Update Stock Transfer", description = "Updates an existing stock transfer record.")
    public ResponseEntity<ApiResponse<StockTransfer>> updateTransfer(@PathVariable UUID id, @RequestBody StockTransfer transfer) {
        transfer.setId(id);
        StockTransfer saved = commandService.saveTransfer(transfer);
        if (saved != null && saved.getWarnings() != null && !saved.getWarnings().isEmpty()) {
            return ResponseEntity.ok(ApiResponse.successWithWarnings(saved, saved.getWarnings()));
        }
        return ResponseEntity.ok(ApiResponse.success(saved));
    }
}
