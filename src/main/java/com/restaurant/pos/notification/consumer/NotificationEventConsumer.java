package com.restaurant.pos.notification.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.event.OrderStatusUpdatedEvent;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.outbox.domain.OutboxEvent;
import com.restaurant.pos.outbox.domain.ProcessedEvent;
import com.restaurant.pos.outbox.processor.OutboxProcessor;
import com.restaurant.pos.outbox.repository.ProcessedEventRepository;
import com.restaurant.pos.push.service.PushNotificationService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Outbox consumer responsible for sending push notifications (FCM) and
 * broadcasting SSE status updates after the order transaction has committed.
 * <p>
 * Never notifies clients of an order change before the transaction is
 * actually committed to the database.
 * <p>
 * Handles event types:
 * <ul>
 *     <li>{@code ORDER_CREATED_NOTIFICATION} — new order push</li>
 *     <li>{@code ORDER_SETTLED_NOTIFICATION} — settled order push + SSE</li>
 *     <li>{@code ORDER_STATUS_UPDATE} — SSE broadcast for status changes</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private static final String CONSUMER_NAME = "NOTIFICATION";

    private final OutboxProcessor outboxProcessor;
    private final PushNotificationService pushNotificationService;
    private final OrderRepository orderRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ApplicationEventPublisher eventPublisher;

    @PostConstruct
    void registerHandlers() {
        outboxProcessor.registerHandler("ORDER_CREATED_NOTIFICATION", this::handleOrderCreatedNotification);
        outboxProcessor.registerHandler("ORDER_SETTLED_NOTIFICATION", this::handleOrderSettledNotification);
        outboxProcessor.registerHandler("ORDER_STATUS_UPDATE", this::handleStatusUpdate);
    }

    private void handleOrderCreatedNotification(OutboxEvent event) {
        if (isAlreadyProcessed(event)) return;

        Order order = loadOrder(event);
        if (order == null) {
            log.warn("[NotificationConsumer] Order not found for event {}, marking processed", event.getId());
            markProcessed(event);
            return;
        }

        try {
            TenantContext.setCurrentTenant(event.getClientId());
            TenantContext.setCurrentOrg(event.getOrgId());
            pushNotificationService.sendNewOrderPush(order);
            log.info("[NotificationConsumer] Sent new order push for order {}", order.getId());
            markProcessed(event);
        } finally {
            TenantContext.clear();
        }
    }

    private void handleOrderSettledNotification(OutboxEvent event) {
        if (isAlreadyProcessed(event)) return;

        Order order = loadOrder(event);
        if (order == null) {
            log.warn("[NotificationConsumer] Order not found for event {}, marking processed", event.getId());
            markProcessed(event);
            return;
        }

        try {
            TenantContext.setCurrentTenant(event.getClientId());
            TenantContext.setCurrentOrg(event.getOrgId());
            pushNotificationService.sendOrderSettledPush(order);
            broadcastSse(event, order);
            log.info("[NotificationConsumer] Sent settled push for order {}", order.getId());
            markProcessed(event);
        } finally {
            TenantContext.clear();
        }
    }

    private void handleStatusUpdate(OutboxEvent event) {
        if (isAlreadyProcessed(event)) return;

        JsonNode payload = outboxProcessor.parsePayload(event);
        UUID orderId = uuidField(payload, "orderId");
        String status = textField(payload, "orderStatus");

        if (orderId != null && status != null) {
            try {
                eventPublisher.publishEvent(new OrderStatusUpdatedEvent(this, orderId, status));
                log.debug("[NotificationConsumer] SSE broadcast for order {} status={}",
                        orderId, status);
            } catch (Exception e) {
                log.warn("[NotificationConsumer] SSE broadcast failed for order {} — {}",
                        orderId, e.getMessage());
            }
        }

        markProcessed(event);
    }

    private void broadcastSse(OutboxEvent event, Order order) {
        try {
            eventPublisher.publishEvent(new OrderStatusUpdatedEvent(
                    this, order.getId(), order.getOrderStatus() != null ? order.getOrderStatus().toString() : null));
        } catch (Exception e) {
            log.warn("[NotificationConsumer] SSE broadcast failed for order {} — {}",
                    order.getId(), e.getMessage());
        }
    }

    private Order loadOrder(OutboxEvent event) {
        JsonNode payload = outboxProcessor.parsePayload(event);
        UUID orderId = uuidField(payload, "orderId");
        if (orderId == null) return null;

        try {
            TenantContext.setCurrentTenant(event.getClientId());
            TenantContext.setCurrentOrg(event.getOrgId());
            if (event.getOrgId() != null) {
                return orderRepository.findByIdAndClientIdAndOrgId(orderId, event.getClientId(), event.getOrgId())
                        .orElseGet(() -> orderRepository.findByIdAndClientId(orderId, event.getClientId()).orElse(null));
            } else {
                return orderRepository.findByIdAndClientId(orderId, event.getClientId()).orElse(null);
            }
        } finally {
            TenantContext.clear();
        }
    }

    private boolean isAlreadyProcessed(OutboxEvent event) {
        return processedEventRepository.existsByConsumerNameAndEventId(CONSUMER_NAME, event.getId());
    }

    private void markProcessed(OutboxEvent event) {
        try {
            processedEventRepository.save(ProcessedEvent.builder()
                    .consumerName(CONSUMER_NAME)
                    .eventId(event.getId())
                    .build());
        } catch (Exception e) {
            log.debug("[NotificationConsumer] Event {} already marked as processed", event.getId());
        }
    }

    private UUID uuidField(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val != null && !val.isNull()) ? UUID.fromString(val.asText()) : null;
    }

    private String textField(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val != null && !val.isNull()) ? val.asText() : null;
    }
}
