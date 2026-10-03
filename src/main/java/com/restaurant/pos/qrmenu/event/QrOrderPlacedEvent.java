package com.restaurant.pos.qrmenu.event;

import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;
import java.util.UUID;

/**
 * Domain event raised when a QR order is placed (or appended to an existing table tab).
 * Handled asynchronously / decoupled by feature listeners (e.g. QrOrderInventoryListener, QrOrderLoyaltyListener).
 */
@Getter
public class QrOrderPlacedEvent extends ApplicationEvent {

    private final Order order;
    private final List<OrderLine> newLines;
    private final UUID clientId;
    private final UUID orgId;
    private final UUID customerId;
    private final boolean isAppended;

    public QrOrderPlacedEvent(Object source, Order order, List<OrderLine> newLines,
                              UUID clientId, UUID orgId, UUID customerId, boolean isAppended) {
        super(source);
        this.order = order;
        this.newLines = newLines;
        this.clientId = clientId;
        this.orgId = orgId;
        this.customerId = customerId;
        this.isAppended = isAppended;
    }
}
