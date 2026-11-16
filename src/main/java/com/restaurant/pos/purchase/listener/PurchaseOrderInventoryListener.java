package com.restaurant.pos.purchase.listener;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.inventory.service.InventoryService;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.purchase.event.PurchaseOrderCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Event Listener for Purchase Order completion.
 * Receives stock into the destination warehouse asynchronously / event-driven.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseOrderInventoryListener {

    private final InventoryService inventoryService;

    @EventListener
    @Transactional
    public void onPurchaseOrderCompleted(PurchaseOrderCompletedEvent event) {
        Order purchaseOrder = event.purchaseOrder();
        if (purchaseOrder == null || purchaseOrder.getLines() == null) {
            return;
        }

        // Only update warehouse inventory if goods have actually been marked as received
        if (!Boolean.TRUE.equals(purchaseOrder.getIsReceived())) {
            log.info("EventListener: Skipping stock intake for PO {} — isReceived is false", purchaseOrder.getOrderNo());
            return;
        }

        java.util.UUID warehouseId = purchaseOrder.getWarehouseId();
        if (warehouseId == null) {
            var defaultWh = inventoryService.findDefaultWarehouse(purchaseOrder.getClientId(), purchaseOrder.getOrgId());
            if (defaultWh.isPresent()) {
                warehouseId = defaultWh.get().getId();
            } else {
                log.warn("EventListener: skipping stock intake for PO {} — no warehouseId set and no default warehouse configured for org {}",
                        purchaseOrder.getOrderNo(), purchaseOrder.getOrgId());
                return;
            }
        }

        // Idempotency: never receive the same purchase order twice (legacy OrderService path may already have).
        if (isPositive(inventoryService.netPurchaseReceivedQty(purchaseOrder.getId()))) {
            log.info("EventListener: Skipping stock intake for PO {} — stock already received", purchaseOrder.getOrderNo());
            return;
        }

        final java.util.UUID targetWarehouseId = warehouseId;

        log.info("EventListener: Processing stock intake | orderNo={} | warehouseId={} | lines={}",
                purchaseOrder.getOrderNo(), targetWarehouseId, purchaseOrder.getLines().size());

        for (OrderLine line : purchaseOrder.getLines()) {
            if (line.getProductId() != null && line.getQuantity() != null && line.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                try {
                    inventoryService.updateStock(
                            targetWarehouseId,
                            line.getProductId(),
                            line.getVariantId(),
                            line.getQuantity(),           // Positive = Stock IN
                            "PURCHASE_RECEIPT",           // Transaction type
                            purchaseOrder.getId(),
                            inventoryService.purchaseUnitCost(purchaseOrder, line),
                            purchaseOrder.getOrgId()
                    );
                } catch (Exception e) {
                    // Rethrow so the whole PO completion rolls back instead of leaving it
                    // completed with partial stock (or failing later with UnexpectedRollbackException).
                    log.error("Failed stock intake for item {} in PO {}: {}",
                            line.getProductId(), purchaseOrder.getOrderNo(), e.getMessage(), e);
                    if (e instanceof BusinessException be) {
                        throw be;
                    }
                    throw new BusinessException("Could not receive stock for " + line.getProductName()
                            + " in PO " + purchaseOrder.getOrderNo() + ": " + e.getMessage());
                }
            }
        }
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
