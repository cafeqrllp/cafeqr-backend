package com.restaurant.pos.inventory.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.inventory.domain.StockAdjustment;
import com.restaurant.pos.inventory.domain.StockSnapshot;
import com.restaurant.pos.inventory.domain.StockTransfer;
import com.restaurant.pos.inventory.domain.StockLedger;
import com.restaurant.pos.inventory.service.InventoryService;
import com.restaurant.pos.inventory.repository.StockAdjustmentRepository;
import com.restaurant.pos.inventory.repository.StockTransferRepository;
import com.restaurant.pos.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
@com.restaurant.pos.subscription.annotation.RequireModule(com.restaurant.pos.subscription.domain.ModuleName.INVENTORY)
public class InventoryController {

    private final InventoryService inventoryService;
    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final StockTransferRepository stockTransferRepository;
    private final com.restaurant.pos.inventory.repository.StockLedgerRepository stockLedgerRepository;
    private final BranchContextService branchContext;

    @GetMapping("/history/{warehouseId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<Page<StockLedger>>> getStockHistory(
            @PathVariable UUID warehouseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "transactionDate"));
        return ResponseEntity.ok(ApiResponse.success(
                stockLedgerRepository.findByWarehouseIdOrderByTransactionDateDesc(warehouseId, pageable)));
    }

    @GetMapping("/stock-overview/{warehouseId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<StockSnapshot>>> getStockOverview(@PathVariable UUID warehouseId) {
        return ResponseEntity.ok(ApiResponse.success(inventoryService.getStockOverview(warehouseId)));
    }

    @GetMapping("/stock-overview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<StockSnapshot>>> getConsolidatedStockOverview(
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID warehouseId) {
        return ResponseEntity.ok(ApiResponse.success(inventoryService.getConsolidatedStockOverview(orgId, warehouseId)));
    }

    // --- Adjustments ---

    @GetMapping("/adjustments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Page<StockAdjustment>>> getAdjustments(
            @RequestParam(required = false) UUID orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID effectiveOrgId = branchContext.getReadOrgId(orgId);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "adjustmentDate"));
        if (effectiveOrgId != null) {
            return ResponseEntity.ok(ApiResponse.success(
                stockAdjustmentRepository.findByClientIdAndOrgIdOrderByAdjustmentDateDesc(clientId, effectiveOrgId, pageable)));
        }
        return ResponseEntity.ok(ApiResponse.success(
            stockAdjustmentRepository.findByClientIdOrderByAdjustmentDateDesc(clientId, pageable)));
    }

    @PostMapping("/adjustments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<StockAdjustment>> createAdjustment(@RequestBody StockAdjustment adjustment) {
        return ResponseEntity.ok(ApiResponse.success(inventoryService.saveAdjustment(adjustment)));
    }

    @PutMapping("/adjustments/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<StockAdjustment>> updateAdjustment(@PathVariable UUID id, @RequestBody StockAdjustment adjustment) {
        adjustment.setId(id);
        return ResponseEntity.ok(ApiResponse.success(inventoryService.saveAdjustment(adjustment)));
    }

    // --- Transfers ---

    @GetMapping("/transfers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Page<StockTransfer>>> getTransfers(
            @RequestParam(required = false) UUID orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID effectiveOrgId = branchContext.getReadOrgId(orgId);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "transferDate"));
        if (effectiveOrgId != null) {
            return ResponseEntity.ok(ApiResponse.success(
                stockTransferRepository.findByClientIdAndOrgIdOrderByTransferDateDesc(clientId, effectiveOrgId, pageable)));
        }
        return ResponseEntity.ok(ApiResponse.success(
            stockTransferRepository.findByClientIdOrderByTransferDateDesc(clientId, pageable)));
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<StockTransfer>> createTransfer(@RequestBody StockTransfer transfer) {
        return ResponseEntity.ok(ApiResponse.success(inventoryService.saveTransfer(transfer)));
    }

    @PutMapping("/transfers/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<StockTransfer>> updateTransfer(@PathVariable UUID id, @RequestBody StockTransfer transfer) {
        transfer.setId(id);
        return ResponseEntity.ok(ApiResponse.success(inventoryService.saveTransfer(transfer)));
    }
}
