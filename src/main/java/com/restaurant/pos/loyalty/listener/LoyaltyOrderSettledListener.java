package com.restaurant.pos.loyalty.listener;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.loyalty.command.LoyaltyCommandService;
import com.restaurant.pos.loyalty.event.LoyaltyOrderSettledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Event listener for order settlement — handles REDEEM and EARN loyalty transactions after order commit.
 * Lives in feature package 'loyalty.listener'.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoyaltyOrderSettledListener {

    private final LoyaltyCommandService commandService;

    @EventListener
    public void onOrderSettled(LoyaltyOrderSettledEvent event) {
        if (event.getCustomerId() == null) return;

        Integer redeemPts = event.getRedeemPoints() != null ? event.getRedeemPoints()
                : (event.getOrder() != null ? event.getOrder().getRedeemPoints() : null);

        log.info("Processing LoyaltyOrderSettledEvent for customer={} order={} eligibleAmount={} redeemPoints={}",
                event.getCustomerId(), event.getOrderId(), event.getEligibleAmount(), redeemPts);

        TenantContext.setCurrentTenant(event.getClientId());
        TenantContext.setCurrentOrg(event.getOrgId());
        try {
            // 1. Process REDEEM transaction if points were redeemed on this order
            if (redeemPts != null && redeemPts > 0) {
                try {
                    commandService.redeemPoints(event.getCustomerId(), event.getOrderId(), redeemPts);
                } catch (Exception ex) {
                    log.error("Loyalty REDEEM failed for orderId={} — swallowing to protect committed order", event.getOrderId(), ex);
                }
            }

            // 2. Process EARN transaction for points earned on eligible order purchase
            if (event.getEligibleAmount() != null && event.getEligibleAmount().compareTo(BigDecimal.ZERO) > 0) {
                try {
                    commandService.earnPoints(event.getCustomerId(), event.getOrderId(), event.getEligibleAmount());
                } catch (Exception ex) {
                    log.error("Loyalty EARN failed for orderId={} — swallowing to protect committed order", event.getOrderId(), ex);
                }
            }
        } finally {
            TenantContext.clear();
        }
    }
}
