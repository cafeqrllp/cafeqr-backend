package com.restaurant.pos.loyalty.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.loyalty.command.LoyaltyCommandService;
import com.restaurant.pos.outbox.domain.OutboxEvent;
import com.restaurant.pos.outbox.domain.ProcessedEvent;
import com.restaurant.pos.outbox.processor.OutboxProcessor;
import com.restaurant.pos.outbox.repository.ProcessedEventRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outbox consumer responsible for executing loyalty operations (earn/reverse)
 * after the order transaction has committed.
 * <p>
 * Idempotent: uses {@code processed_events} to prevent duplicate loyalty transactions.
 * <p>
 * Handles event types:
 * <ul>
 *     <li>{@code ORDER_SETTLED} — earn points (redemption is synchronous in OrderService)</li>
 *     <li>{@code ORDER_CANCELLED} — reverse loyalty transactions</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoyaltyEventConsumer {

    private static final String CONSUMER_NAME = "LOYALTY";

    private final OutboxProcessor outboxProcessor;
    private final LoyaltyCommandService loyaltyCommandService;
    private final ProcessedEventRepository processedEventRepository;

    @PostConstruct
    void registerHandlers() {
        outboxProcessor.registerHandler("ORDER_SETTLED", this::handleOrderSettled);
        outboxProcessor.registerHandler("ORDER_CANCELLED", this::handleOrderCancelled);
    }

    private void handleOrderSettled(OutboxEvent event) {
        if (isAlreadyProcessed(event)) {
            return;
        }

        JsonNode payload = outboxProcessor.parsePayload(event);

        UUID customerId = uuidField(payload, "customerId");
        UUID orderId = uuidField(payload, "orderId");

        if (customerId == null) {
            log.debug("[LoyaltyConsumer] No customer attached to ORDER_SETTLED event, skipping. orderId={}",
                    orderId);
            markProcessed(event);
            return;
        }

        TenantContext.setCurrentTenant(event.getClientId());
        TenantContext.setCurrentOrg(event.getOrgId());
        try {
            // Redemption is NOT done here: it runs synchronously in the order transaction
            // (OrderService), so a failed redemption fails the sale with a clear reason.

            // EARN for the eligible amount. "eligibleAmount" is the legacy key written by older
            // QR events still sitting in the outbox.
            BigDecimal eligibleAmount = decimalField(payload, "loyaltyEligibleAmount");
            if (eligibleAmount == null) {
                eligibleAmount = decimalField(payload, "eligibleAmount");
            }
            if (eligibleAmount != null && eligibleAmount.compareTo(BigDecimal.ZERO) > 0) {
                loyaltyCommandService.earnPoints(customerId, orderId, eligibleAmount);
                log.info("[LoyaltyConsumer] Earned points for customer={} order={} eligible={}",
                        customerId, orderId, eligibleAmount);
            }

            markProcessed(event);
        } finally {
            TenantContext.clear();
        }
    }

    private void handleOrderCancelled(OutboxEvent event) {
        if (isAlreadyProcessed(event)) {
            return;
        }

        JsonNode payload = outboxProcessor.parsePayload(event);
        UUID customerId = uuidField(payload, "customerId");
        UUID orderId = uuidField(payload, "orderId");

        if (customerId == null) {
            markProcessed(event);
            return;
        }

        TenantContext.setCurrentTenant(event.getClientId());
        TenantContext.setCurrentOrg(event.getOrgId());
        try {
            loyaltyCommandService.reverseOrderTransactions(orderId);
            log.info("[LoyaltyConsumer] Reversed loyalty for customer={} order={}",
                    customerId, orderId);
            markProcessed(event);
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
            // Duplicate key is fine — means it was already processed
            log.debug("[LoyaltyConsumer] Event {} already marked as processed", event.getId());
        }
    }

    private UUID uuidField(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val != null && !val.isNull()) ? UUID.fromString(val.asText()) : null;
    }

    private BigDecimal decimalField(JsonNode node, String field) {
        JsonNode val = node.get(field);
        return (val != null && !val.isNull()) ? new BigDecimal(val.asText()) : null;
    }
}
