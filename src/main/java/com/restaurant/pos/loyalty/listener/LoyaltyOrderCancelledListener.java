package com.restaurant.pos.loyalty.listener;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.loyalty.command.LoyaltyCommandService;
import com.restaurant.pos.loyalty.event.LoyaltyOrderCancelledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Event listener for order cancellation — reverses loyalty points after order commit.
 * Lives in feature package 'loyalty.listener'.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoyaltyOrderCancelledListener {

    private final LoyaltyCommandService commandService;

    @EventListener
    public void onOrderCancelled(LoyaltyOrderCancelledEvent event) {
        log.info("Processing LoyaltyOrderCancelledEvent for order={}", event.getOrderId());

        UUID prevTenant = TenantContext.getCurrentTenant();
        UUID prevOrg = TenantContext.getCurrentOrg();
        try {
            if (event.getClientId() != null) {
                TenantContext.setCurrentTenant(event.getClientId());
            }
            if (event.getOrgId() != null) {
                TenantContext.setCurrentOrg(event.getOrgId());
            }
            commandService.reverseOrderTransactions(event.getOrderId(), event.getClientId(), event.getOrgId());
        } catch (Exception ex) {
            log.error("Loyalty reversal failed for orderId={} — swallowing to protect committed order", event.getOrderId(), ex);
        } finally {
            TenantContext.setCurrentTenant(prevTenant);
            TenantContext.setCurrentOrg(prevOrg);
        }
    }
}
