package com.restaurant.pos.loyalty.event;

import com.restaurant.pos.order.domain.Order;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event raised when a SALE order is cancelled or voided.
 * Lives in feature package 'loyalty.event'.
 */
@Getter
public class LoyaltyOrderCancelledEvent extends ApplicationEvent {

    private final UUID orderId;
    private final UUID customerId;
    private final UUID clientId;
    private final UUID orgId;

    public LoyaltyOrderCancelledEvent(Object source, Order order) {
        super(source);
        this.orderId    = order.getId();
        this.customerId = order.getCustomerId();
        this.clientId   = order.getClientId();
        this.orgId      = order.getOrgId();
    }
}
