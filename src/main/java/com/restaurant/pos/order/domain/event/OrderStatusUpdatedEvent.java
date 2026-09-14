package com.restaurant.pos.order.domain.event;

import java.util.UUID;

/**
 * Spring Application Event published when an order's status changes.
 * <p>
 * This decouples the core notification module from the optional delivery module.
 * When the delivery module is present, its {@code OrderStatusSseController}
 * listens for this event and broadcasts SSE updates. When the delivery module
 * is absent (e.g. in production), the event is simply ignored.
 */
public class OrderStatusUpdatedEvent extends org.springframework.context.ApplicationEvent {

    private final UUID orderId;
    private final String status;

    public OrderStatusUpdatedEvent(Object source, UUID orderId, String status) {
        super(source);
        this.orderId = orderId;
        this.status = status;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getStatus() {
        return status;
    }
}
