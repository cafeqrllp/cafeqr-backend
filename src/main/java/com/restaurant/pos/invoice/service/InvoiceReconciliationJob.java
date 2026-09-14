package com.restaurant.pos.invoice.service;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reconciliation job that discovers COMPLETED orders missing invoices and
 * generates them in the background.
 * <p>
 * This replaces the previous approach of lazily generating invoices inside
 * GET endpoints ({@code getOrders()}) which was a side effect in a read
 * operation and could cause slow reads and unexpected write-locks.
 * <p>
 * Runs every 15 minutes during off-peak hours to avoid contention with
 * live POS traffic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceReconciliationJob {

    private final JdbcTemplate jdbcTemplate;
    private final OrderRepository orderRepository;
    private final OrderService orderService;

    /**
     * Scans for COMPLETED sale orders that have no invoice (invoice_no IS NULL
     * or empty) and generates them. Processes up to 100 orders per cycle to
     * avoid long-running transactions.
     * <p>
     * Runs every 15 minutes. Non-overlapping: if the previous invocation is
     * still running, the scheduler will skip this trigger.
     */
    @Scheduled(fixedDelay = 900_000, initialDelay = 60_000)
    public void reconcileMissingInvoices() {
        log.debug("[InvoiceReconciliation] Starting reconciliation sweep...");

        List<Map<String, Object>> candidates = jdbcTemplate.queryForList("""
                SELECT o.id, o.client_id, o.org_id
                FROM orders o
                WHERE o.order_status = 'COMPLETED'
                  AND o.isactive = 'Y'
                  AND NOT EXISTS (SELECT 1 FROM invoices i WHERE i.order_id = o.id)
                ORDER BY o.created_at ASC
                FOR UPDATE SKIP LOCKED
                LIMIT 100
                """);

        if (candidates.isEmpty()) {
            log.debug("[InvoiceReconciliation] No orders with missing invoices found.");
            return;
        }

        log.info("[InvoiceReconciliation] Found {} orders with missing invoices, processing...",
                candidates.size());

        int generated = 0;
        int failed = 0;

        for (Map<String, Object> row : candidates) {
            UUID orderId = (UUID) row.get("id");
            UUID clientId = (UUID) row.get("client_id");
            UUID orgId = (UUID) row.get("org_id");

            try {
                TenantContext.setCurrentTenant(clientId);
                TenantContext.setCurrentOrg(orgId);
                Order order = (orgId != null)
                        ? orderRepository.findByIdAndClientIdAndOrgId(orderId, clientId, orgId).orElse(null)
                        : orderRepository.findByIdAndClientId(orderId, clientId).orElse(null);

                if (order == null) {
                    continue;
                }

                // Double-check: skip if invoice was generated between query and fetch
                if (order.getInvoiceNo() != null && !order.getInvoiceNo().isEmpty()) {
                    continue;
                }

                orderService.generateInvoice(order);
                generated++;
                log.debug("[InvoiceReconciliation] Generated invoice for order {}", orderId);
            } catch (Exception e) {
                failed++;
                log.warn("[InvoiceReconciliation] Failed to generate invoice for order {} — {}",
                        orderId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }

        log.info("[InvoiceReconciliation] Completed: {} generated, {} failed out of {} candidates",
                generated, failed, candidates.size());
    }
}
