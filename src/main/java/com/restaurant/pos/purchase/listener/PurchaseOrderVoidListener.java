 package com.restaurant.pos.purchase.listener;

import com.restaurant.pos.inventory.service.InventoryService;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.purchase.event.PurchaseOrderVoidedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Event Listener for Purchase Order voiding and re-editing.
 * Decouples voiding of linked Vendor Bills (invoices) and Outbound Payments,
 * and automatically reverses warehouse inventory stock intake.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseOrderVoidListener {

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final InventoryService inventoryService;

    @EventListener
    @Transactional
    public void onPurchaseOrderVoided(PurchaseOrderVoidedEvent event) {
        Order purchaseOrder = event.purchaseOrder();
        if (purchaseOrder == null || purchaseOrder.getId() == null) {
            return;
        }

        UUID orderId = purchaseOrder.getId();
        log.info("PurchaseOrderVoidListener: Processing void event | orderNo={} | orderId={}",
                purchaseOrder.getOrderNo(), orderId);

        try {
            // 1. Void linked Invoices (Vendor Bills)
            List<Invoice> invoices = invoiceRepository.findByOrderId(orderId);
            if (invoices != null && !invoices.isEmpty()) {
                for (Invoice invoice : invoices) {
                    invoice.setStatus("VOID");
                    invoice.setDocStatus("VOID");
                    invoice.setIsactive("N");
                    invoiceRepository.save(invoice);
                    log.info("PurchaseOrderVoidListener: Voided Vendor Bill | billNo={} | orderId={}",
                            invoice.getInvoiceNo(), orderId);
                }
            }

            // 2. Void linked Payments
            List<Payment> payments = paymentRepository.findByOrderId(orderId);
            if (payments != null && !payments.isEmpty()) {
                for (Payment payment : payments) {
                    payment.setDocStatus("VOID");
                    payment.setIsactive("N");
                    paymentRepository.save(payment);
                    log.info("PurchaseOrderVoidListener: Voided Outbound Payment | refNo={} | orderId={}",
                            payment.getReferenceNo(), orderId);
                }
            }

            // 3. Reverse inventory stock intake if lines existed
            reverseStockIntake(purchaseOrder);

        } catch (Exception e) {
            log.error("PurchaseOrderVoidListener: Failed during void processing for PO {}: {}",
                    purchaseOrder.getOrderNo(), e.getMessage(), e);
        }
    }

    private void reverseStockIntake(Order order) {
        if (order == null || order.getLines() == null || order.getLines().isEmpty()) {
            return;
        }

        UUID warehouseId = order.getWarehouseId();
        if (warehouseId == null) {
            var defaultWh = inventoryService.findDefaultWarehouse(order.getClientId(), order.getOrgId());
            if (defaultWh.isPresent()) {
                warehouseId = defaultWh.get().getId();
            } else {
                log.warn("PurchaseOrderVoidListener: Cannot reverse stock intake for PO {} — no warehouse found",
                        order.getOrderNo());
                return;
            }
        }

        final UUID targetWhId = warehouseId;
        for (OrderLine line : order.getLines()) {
            if (line.getProductId() != null && line.getQuantity() != null && line.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                try {
                    inventoryService.updateStock(
                            targetWhId,
                            line.getProductId(),
                            line.getVariantId(),
                            line.getQuantity().negate(), // Stock reduction on void/edit
                            "PURCHASE_VOID",
                            order.getId(),
                            line.getUnitPrice(),
                            order.getOrgId()
                    );
                    log.info("PurchaseOrderVoidListener: Reversed stock for productId={} | qty={}",
                            line.getProductId(), line.getQuantity());
                } catch (Exception e) {
                    log.error("PurchaseOrderVoidListener: Failed to reverse stock for productId {} in PO {}: {}",
                            line.getProductId(), order.getOrderNo(), e.getMessage(), e);
                }
            }
        }
    }
}
