package com.restaurant.pos.loyalty.listener;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.loyalty.command.LoyaltyCommandService;
import com.restaurant.pos.loyalty.event.LoyaltyOrderSettledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Awards loyalty points once a settled sale has been committed.
 * <p>
 * Runs AFTER_COMMIT in its own transaction, so a loyalty failure can never roll back or block
 * the sale. Redemption is NOT handled here: it runs synchronously inside the order transaction
 * (OrderService) so a failed redemption fails the order with a clear reason.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoyaltyOrderSettledListener {

    private final LoyaltyCommandService commandService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderSettled(LoyaltyOrderSettledEvent event) {
        if (event.getCustomerId() == null) return;
        if (event.getEligibleAmount() == null || event.getEligibleAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        log.info("Processing LoyaltyOrderSettledEvent for customer={} order={} eligibleAmount={}",
                event.getCustomerId(), event.getOrderId(), event.getEligibleAmount());

        UUID prevTenant = TenantContext.getCurrentTenant();
        UUID prevOrg = TenantContext.getCurrentOrg();
        try {
            TenantContext.setCurrentTenant(event.getClientId());
            TenantContext.setCurrentOrg(event.getOrgId());
            commandService.earnPointsInNewTransaction(event.getCustomerId(), event.getOrderId(), event.getEligibleAmount());
        } catch (Exception ex) {
            log.error("Loyalty EARN failed for orderId={} — order is already committed", event.getOrderId(), ex);
        } finally {
            TenantContext.setCurrentTenant(prevTenant);
            TenantContext.setCurrentOrg(prevOrg);
        }
    }
}
