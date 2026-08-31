package com.restaurant.pos.loyalty.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Published when customer earns loyalty points.
 * Lives in feature-based package 'loyalty.event'.
 */
@Getter
public class LoyaltyPointsEarnedEvent extends ApplicationEvent {

    private final UUID customerId;
    private final UUID orderId;
    private final int pointsEarned;
    private final int newBalance;

    public LoyaltyPointsEarnedEvent(Object source, UUID customerId, UUID orderId, int pointsEarned, int newBalance) {
        super(source);
        this.customerId = customerId;
        this.orderId = orderId;
        this.pointsEarned = pointsEarned;
        this.newBalance = newBalance;
    }
}
