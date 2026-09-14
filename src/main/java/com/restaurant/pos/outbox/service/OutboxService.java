package com.restaurant.pos.outbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.outbox.domain.OutboxEvent;
import com.restaurant.pos.outbox.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Service for enqueueing domain events into the transactional outbox.
 * <p>
 * <strong>Critical invariant:</strong> This service must be called within
 * the same {@code @Transactional} boundary as the business operation.
 * The outbox row is committed (or rolled back) atomically with the order,
 * invoice, payment, and accounting changes.
 * <p>
 * Example usage:
 * <pre>{@code
 * @Transactional
 * public Order settleOrder(UUID id, OrderSettleRequest request) {
 *     // ... save order, invoice, payment, accounting ...
 *     outboxService.enqueue("ORDER", saved.getId(), "ORDER_SETTLED",
 *             saved.getClientId(), saved.getOrgId(), payloadObject);
 *     return saved;
 * }
 * }</pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Enqueues an event into the outbox within the current transaction.
     *
     * @param aggregateType the type of aggregate (e.g., "ORDER", "INVOICE")
     * @param aggregateId   the ID of the aggregate
     * @param eventType     the type of event (e.g., "ORDER_SETTLED", "ORDER_BILLED")
     * @param clientId      the tenant ID
     * @param orgId         the organization/branch ID (nullable)
     * @param payload       the event payload object (will be serialized to JSON)
     */
    public void enqueue(String aggregateType, UUID aggregateId, String eventType,
                        UUID clientId, UUID orgId, Object payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);

            OutboxEvent event = OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .clientId(clientId)
                    .orgId(orgId)
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(payloadJson)
                    .status("PENDING")
                    .attempts(0)
                    .availableAt(Instant.now())
                    .createdAt(Instant.now())
                    .build();

            outboxRepository.save(event);

            log.debug("Outbox event enqueued: type={}, aggregateId={}, eventType={}",
                    aggregateType, aggregateId, eventType);
        } catch (Exception e) {
            log.error("Failed to enqueue outbox event: type={}, aggregateId={}, eventType={}",
                    aggregateType, aggregateId, eventType, e);
            throw new RuntimeException("Failed to serialize outbox event payload", e);
        }
    }

    /**
     * Convenience overload for events without an org scope.
     */
    public void enqueue(String aggregateType, UUID aggregateId, String eventType,
                        UUID clientId, Object payload) {
        enqueue(aggregateType, aggregateId, eventType, clientId, null, payload);
    }
}
