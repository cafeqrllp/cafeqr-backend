package com.restaurant.pos.inventory.service;

import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.accounting.service.AccountingPostingService;
import com.restaurant.pos.inventory.domain.*;
import com.restaurant.pos.inventory.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final WarehouseRepository warehouseRepository;
    private final StockLedgerRepository stockLedgerRepository;
    private final StockSnapshotRepository stockSnapshotRepository;
    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final StockTransferRepository stockTransferRepository;
    private final AccountingPostingService accountingPostingService;
    private final BranchContextService branchContext;

    // --- Warehouse Management ---

    public List<Warehouse> getWarehouses() {
        UUID clientId = TenantContext.getCurrentTenant();
        if (SecurityUtils.isSuperAdmin()) {
            return warehouseRepository.findByClientIdOrderByCreatedAtDesc(clientId);
        }
        return warehouseRepository.findByClientIdAndOrgIdOrGlobalOrderByCreatedAtDesc(clientId, TenantContext.getCurrentOrg());
    }

    public Warehouse getWarehouse(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        if (SecurityUtils.isSuperAdmin()) {
            return warehouseRepository.findByIdAndClientId(id, clientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found"));
        }
        return warehouseRepository.findByIdAndClientIdAndOrgIdOrGlobal(id, clientId, TenantContext.getCurrentOrg())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found for ID: " + id));
    }

    @Transactional
    public Warehouse saveWarehouse(Warehouse warehouse) {
        warehouse.setClientId(TenantContext.getCurrentTenant());
        warehouse.setOrgId(branchContext.requireWriteOrgId(warehouse.getOrgId()));
        return warehouseRepository.save(warehouse);
    }

    @Transactional
    public void deleteWarehouse(UUID id) {
        Warehouse warehouse = getWarehouse(id);
        if (warehouse != null) {
            warehouseRepository.delete(warehouse);
        }
    }

    // --- Core Stock Logic (Ledger & Snapshots) ---

    @Transactional
    public void updateStock(UUID warehouseId, UUID productId, UUID variantId,
                             BigDecimal quantityChange, String transactionType,
                             UUID referenceId, BigDecimal unitCost) {
        UUID orgId = branchContext.requireWriteOrgId(TenantContext.getCurrentOrg());
        updateStock(warehouseId, productId, variantId, quantityChange, transactionType, referenceId, unitCost, orgId);
    }

    /**
     * Stock update with an explicit orgId — use this when the org is already known from the
     * source document (e.g. a completed Purchase Order) rather than relying on TenantContext,
     * which may not carry the correct branch during nested transactional calls.
     */
    @Transactional
    public void updateStock(UUID warehouseId, UUID productId, UUID variantId,
                             BigDecimal quantityChange, String transactionType,
                             UUID referenceId, BigDecimal unitCost, UUID explicitOrgId) {

        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = explicitOrgId != null ? explicitOrgId
                : branchContext.requireWriteOrgId(TenantContext.getCurrentOrg());

        // 1. Get current balance from snapshot (or 0)
        StockSnapshot snapshot = stockSnapshotRepository
                .findByWarehouseIdAndProductIdAndVariantId(warehouseId, productId, variantId)
                .orElseGet(() -> StockSnapshot.builder()
                        .clientId(clientId)
                        .orgId(orgId)
                        .warehouseId(warehouseId)
                        .productId(productId)
                        .variantId(variantId)
                        .currentQuantity(BigDecimal.ZERO)
                        .build());

        // 2. Calculate new balance
        BigDecimal newBalance = snapshot.getCurrentQuantity().add(quantityChange);
        snapshot.setCurrentQuantity(newBalance);
        snapshot.setLastUpdated(LocalDateTime.now());
        stockSnapshotRepository.save(snapshot);

        // 3. Log to Ledger
        StockLedger ledger = StockLedger.builder()
                .clientId(clientId)
                .orgId(orgId)
                .warehouseId(warehouseId)
                .productId(productId)
                .variantId(variantId)
                .transactionType(transactionType)
                .referenceId(referenceId)
                .quantityChange(quantityChange)
                .balanceAfterTransaction(newBalance)
                .unitCost(unitCost)
                .createdBy(SecurityUtils.getCurrentUserId())
                .build();
        stockLedgerRepository.save(ledger);
    }

    public List<StockSnapshot> getStockOverview(UUID warehouseId) {
        return stockSnapshotRepository.findByWarehouseId(warehouseId);
    }

    // --- Stock Adjustments ---

    @Transactional
    public StockAdjustment saveAdjustment(StockAdjustment adjustment) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.requireWriteOrgId(adjustment.getOrgId());
        
        adjustment.setClientId(clientId);
        adjustment.setOrgId(orgId);

        if (adjustment.getAdjustmentNumber() == null) {
            adjustment.setAdjustmentNumber("ADJ-" + System.currentTimeMillis());
        }

        // Process lines if completed
        if ("COMPLETED".equalsIgnoreCase(adjustment.getStatus())) {
            for (StockAdjustmentLine line : adjustment.getLines()) {
                updateStock(adjustment.getWarehouseId(), line.getProductId(), line.getVariantId(), 
                        line.getQuantityChange(), "ADJUSTMENT", adjustment.getId(), line.getUnitCost());
            }
        }
        
        StockAdjustment saved = stockAdjustmentRepository.save(adjustment);
        accountingPostingService.postStockAdjustment(saved);
        return saved;
    }

    // --- Stock Transfers ---

    @Transactional
    public StockTransfer saveTransfer(StockTransfer transfer) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.requireWriteOrgId(transfer.getOrgId());
        
        transfer.setClientId(clientId);
        transfer.setOrgId(orgId);

        if (transfer.getTransferNumber() == null) {
            transfer.setTransferNumber("TRF-" + System.currentTimeMillis());
        }

        // Process inventory movement if completed
        if ("COMPLETED".equalsIgnoreCase(transfer.getStatus())) {
            for (StockTransferLine line : transfer.getLines()) {
                // Deduct from source
                updateStock(transfer.getSourceWarehouseId(), line.getProductId(), line.getVariantId(), 
                        line.getTransferQuantity().negate(), "TRANSFER_OUT", transfer.getId(), BigDecimal.ZERO);
                
                // Add to destination
                updateStock(transfer.getDestWarehouseId(), line.getProductId(), line.getVariantId(), 
                        line.getTransferQuantity(), "TRANSFER_IN", transfer.getId(), BigDecimal.ZERO);
            }
        }

        return stockTransferRepository.save(transfer);
    }
}
