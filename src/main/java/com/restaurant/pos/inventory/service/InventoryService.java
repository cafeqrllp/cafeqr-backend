package com.restaurant.pos.inventory.service;

import com.restaurant.pos.inventory.command.InventoryCommandService;
import com.restaurant.pos.inventory.domain.StockAdjustment;
import com.restaurant.pos.inventory.domain.StockSnapshot;
import com.restaurant.pos.inventory.domain.StockTransfer;
import com.restaurant.pos.inventory.query.InventoryQueryService;
import com.restaurant.pos.warehouse.command.WarehouseCommandService;
import com.restaurant.pos.warehouse.domain.Warehouse;
import com.restaurant.pos.warehouse.query.WarehouseQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Unified Inventory Service Façade delegating CQRS commands and queries.
 * Maintains complete backwards compatibility for existing callers (OrderService,Listeners,etc.).
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryCommandService commandService;
    private final InventoryQueryService queryService;
    private final WarehouseQueryService warehouseQueryService;
    private final WarehouseCommandService warehouseCommandService;

    public Optional<Warehouse> findDefaultWarehouse(UUID clientId, UUID orgId) {
        return warehouseQueryService.findDefaultWarehouse(clientId, orgId);
    }

    // --- Warehouse Management ---

    public List<Warehouse> getWarehouses(UUID orgId) {
        return warehouseQueryService.getWarehouses(orgId);
    }

    public List<Warehouse> getWarehouses() {
        return warehouseQueryService.getWarehouses();
    }

    public Warehouse getWarehouse(UUID id) {
        return warehouseQueryService.getWarehouse(id);
    }

    @Transactional
    public void deleteWarehouse(UUID id) {
        warehouseCommandService.deleteWarehouse(id);
    }

    // --- Core Stock Commands ---

    @Transactional
    public void updateStock(UUID warehouseId, UUID productId, UUID variantId,
                             BigDecimal quantityChange, String transactionType,
                             UUID referenceId, BigDecimal unitCost) {
        commandService.updateStock(warehouseId, productId, variantId, quantityChange, transactionType, referenceId, unitCost);
    }

    @Transactional
    public void updateStock(UUID warehouseId, UUID productId, UUID variantId,
                             BigDecimal quantityChange, String transactionType,
                             UUID referenceId, BigDecimal unitCost, UUID explicitOrgId) {
        commandService.updateStock(warehouseId, productId, variantId, quantityChange, transactionType, referenceId, unitCost, explicitOrgId, null);
    }

    @Transactional
    public void updateStock(UUID warehouseId, UUID productId, UUID variantId,
                             BigDecimal quantityChange, String transactionType,
                             UUID referenceId, BigDecimal unitCost, UUID explicitOrgId, UUID explicitClientId) {
        commandService.updateStock(warehouseId, productId, variantId, quantityChange, transactionType, referenceId, unitCost, explicitOrgId, explicitClientId);
    }

    @Transactional
    public StockAdjustment saveAdjustment(StockAdjustment adjustment) {
        return commandService.saveAdjustment(adjustment);
    }

    @Transactional
    public StockTransfer saveTransfer(StockTransfer transfer) {
        return commandService.saveTransfer(transfer);
    }

    // --- Core Stock Queries ---

    @Transactional(readOnly = true)
    public List<StockSnapshot> getStockOverview(UUID warehouseId) {
        return queryService.getStockOverview(warehouseId);
    }

    @Transactional(readOnly = true)
    public List<StockSnapshot> getConsolidatedStockOverview(UUID orgId, UUID warehouseId) {
        return queryService.getConsolidatedStockOverview(orgId, warehouseId);
    }

    @Transactional(readOnly = true)
    public Optional<StockSnapshot> findStockSnapshot(UUID warehouseId, UUID productId, UUID variantId) {
        return commandService.findStockSnapshot(warehouseId, productId, variantId);
    }
}
