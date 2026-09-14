package com.restaurant.pos.print.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.outbox.domain.OutboxEvent;
import com.restaurant.pos.outbox.domain.ProcessedEvent;
import com.restaurant.pos.outbox.processor.OutboxProcessor;
import com.restaurant.pos.outbox.repository.ProcessedEventRepository;
import com.restaurant.pos.print.domain.PrintJobKind;
import com.restaurant.pos.print.service.PrintJobService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Outbox consumer responsible for dispatching cloud print jobs after the
 * order transaction has committed.
 * <p>
 * By executing print operations asynchronously via the outbox, a printer
 * connection timeout or failure cannot cause an order transaction to roll back.
 * <p>
 * Handles event types:
 * <ul>
 *     <li>{@code ORDER_CONFIRMED} — KOT print</li>
 *     <li>{@code ORDER_BILLED} — Bill print</li>
 *     <li>{@code ORDER_SETTLED} — Receipt/Bill print</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrintEventConsumer {

    private static final String CONSUMER_NAME = "PRINT";

    private final OutboxProcessor outboxProcessor;
    private final PrintJobService printJobService;
    private final OrderRepository orderRepository;
    private final ProcessedEventRepository processedEventRepository;

    @PostConstruct
    void registerHandlers() {
        outboxProcessor.registerHandler("ORDER_CONFIRMED", this::handleOrderConfirmed);
        outboxProcessor.registerHandler("ORDER_BILLED", this::handleOrderBilled);
        // Note: ORDER_SETTLED is handled by LoyaltyEventConsumer for loyalty
        // and by this consumer for printing — but the outbox processor only
        // dispatches to ONE handler per event type. We'll handle printing
        // for settled orders through the ORDER_SETTLED_PRINT event type.
        outboxProcessor.registerHandler("ORDER_SETTLED_PRINT", this::handleOrderSettledPrint);
    }

    private void handleOrderConfirmed(OutboxEvent event) {
        if (isAlreadyProcessed(event)) return;

        Order order = loadOrder(event);
        if (order == null) {
            markProcessed(event);
            return;
        }

        try {
            printJobService.enqueueForOrder(order, PrintJobKind.KOT, "outbox");
            log.info("[PrintConsumer] Enqueued KOT print job for order {}", order.getId());
        } catch (Exception e) {
            log.error("[PrintConsumer] Failed to enqueue KOT print for order {} — {}",
                    order.getId(), e.getMessage(), e);
            throw e; // Let outbox retry
        }

        markProcessed(event);
    }

    private void handleOrderBilled(OutboxEvent event) {
        if (isAlreadyProcessed(event)) return;

        Order order = loadOrder(event);
        if (order == null) {
            markProcessed(event);
            return;
        }

        try {
            printJobService.enqueueForOrder(order, PrintJobKind.BILL, "outbox");
            log.info("[PrintConsumer] Enqueued BILL print job for order {}", order.getId());
        } catch (Exception e) {
            log.error("[PrintConsumer] Failed to enqueue BILL print for order {} — {}",
                    order.getId(), e.getMessage(), e);
            throw e;
        }

        markProcessed(event);
    }

    private void handleOrderSettledPrint(OutboxEvent event) {
        if (isAlreadyProcessed(event)) return;

        Order order = loadOrder(event);
        if (order == null) {
            markProcessed(event);
            return;
        }

        try {
            printJobService.enqueueForOrder(order, PrintJobKind.BILL, "outbox");
            log.info("[PrintConsumer] Enqueued receipt print job for settled order {}", order.getId());
        } catch (Exception e) {
            log.error("[PrintConsumer] Failed to enqueue receipt print for order {} — {}",
                    order.getId(), e.getMessage(), e);
            throw e;
        }

        markProcessed(event);
    }

    private Order loadOrder(OutboxEvent event) {
        JsonNode payload = outboxProcessor.parsePayload(event);
        UUID orderId = uuidField(payload, "orderId");
        if (orderId == null) {
            log.warn("[PrintConsumer] No orderId in event {}", event.getId());
            return null;
        }

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
            log.debug("[PrintConsumer] Event {} already marked as processed", event.getId());
        }
    }

    private UUID uuidField(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val != null && !val.isNull()) ? UUID.fromString(val.asText()) : null;
    }
}
