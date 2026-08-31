package com.restaurant.pos.loyalty.event;

import com.restaurant.pos.order.domain.Order;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Event raised when a SALE order is settled/completed with payment.
 * Lives in feature package 'loyalty.event'.
 */
@Getter
public class LoyaltyOrderSettledEvent extends ApplicationEvent {

    private final Order order;
    private final UUID orderId;
    private final UUID customerId;
    private final UUID clientId;
    private final UUID orgId;
    private final BigDecimal eligibleAmount;
    private final Integer redeemPoints;

    public LoyaltyOrderSettledEvent(Object source, Order order, UUID customerId, BigDecimal eligibleAmount) {
        super(source);
        this.order          = order;
        this.orderId        = order != null ? order.getId() : null;
        this.customerId     = customerId != null ? customerId : (order != null ? order.getCustomerId() : null);
        this.clientId       = order != null ? order.getClientId() : null;
        this.orgId          = order != null ? order.getOrgId() : null;
        this.eligibleAmount = eligibleAmount;
        this.redeemPoints   = order != null ? order.getRedeemPoints() : null;
    }

    public LoyaltyOrderSettledEvent(Object source, Order order, BigDecimal eligibleAmount) {
        this(source, order, order != null ? order.getCustomerId() : null, eligibleAmount);
    }
}
