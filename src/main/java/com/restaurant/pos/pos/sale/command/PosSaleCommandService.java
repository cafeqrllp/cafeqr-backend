package com.restaurant.pos.pos.sale.command;

import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.dto.CreateOrderRequest;
import com.restaurant.pos.order.dto.IdempotentCreateResult;
import com.restaurant.pos.order.dto.OrderDtoMapper;
import com.restaurant.pos.order.dto.OrderResponseDto;
import com.restaurant.pos.order.service.OrderRequestFingerprintService;
import com.restaurant.pos.order.service.OrderService;
import com.restaurant.pos.outbox.service.OutboxService;
import com.restaurant.pos.pos.sale.query.PosCacheInvalidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * POS Sale Command Service — the "Write Side" of in-process CQRS for POS sales.
 * <p>
 * Implements transaction-first order creation with transactional outbox:
 * <ul>
 *   <li>Same transaction: order, order lines, inventory, invoice, payment, accounting, outbox rows</li>
 *   <li>Domain events: enqueued to transactional outbox in the same DB transaction (failures roll back)</li>
 *   <li>After commit: printing, notifications, and loyalty are dispatched asynchronously</li>
 *   <li>Cache invalidation: scheduled strictly after transaction commit</li>
 *   <li>Idempotency: safe for duplicate submissions via sourceLocalRef / idempotency key</li>
 * </ul>
 * <p>
 * The existing legacy order module ({@code com.restaurant.pos.order.*}) remains
 * clean of asynchronous side effects in the transactional core.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PosSaleCommandService {

    private final OrderService orderService;
    private final OrderDtoMapper orderDtoMapper;
    private final OrderRequestFingerprintService fingerprintService;
    private final OutboxService outboxService;
    private final PosCacheInvalidationService cacheInvalidationService;

    /**
     * Creates a new sale order using the transaction-first pipeline and transactional outbox.
     *
     * @param request        the order creation request DTO from the frontend
     * @param idempotencyKey optional Idempotency-Key header value
     * @return result containing the created/deduplicated order and a created flag
     */
    @Transactional
    public CreateSaleResult createSaleOrder(CreateOrderRequest request, String idempotencyKey) {
        log.info("POS Sale: creating sale order | sourceLocalRef={} | hasIdempotencyKey={}",
                request.getSourceLocalRef(),
                StringUtils.hasText(idempotencyKey));

        // Validate idempotency key consistency
        if (StringUtils.hasText(idempotencyKey)
                && StringUtils.hasText(request.getSourceLocalRef())
                && !idempotencyKey.equals(request.getSourceLocalRef())) {
            throw new com.restaurant.pos.common.exception.BusinessException(
                    "Idempotency-Key does not match sourceLocalRef");
        }

        String sourceLocalRef = StringUtils.hasText(idempotencyKey)
                ? idempotencyKey
                : request.getSourceLocalRef();

        // Map DTO → Entity
        String fingerprint = fingerprintService.fingerprint(request);
        Order mappedEntity = orderDtoMapper.toEntity(request);
        mappedEntity.setRequestFingerprint(fingerprint);
        mappedEntity.setSourceLocalRef(sourceLocalRef);

        // Delegate core transactional creation (order, lines, inventory, invoice, payment, accounting)
        IdempotentCreateResult result = orderService.createOrderIdempotently(mappedEntity);
        Order savedOrder = result.order();

        // If newly created, enqueue outbox events within the same transaction.
        // Failures are NOT caught here: an outbox failure must roll back the transaction.
        if (result.created() && savedOrder != null && savedOrder.getId() != null) {
            enqueueOutboxEvents(savedOrder, request);

            // Invalidate POS query caches strictly AFTER commit to prevent cache inconsistency
            schedulePostCommitCacheInvalidation(savedOrder.getClientId(), savedOrder.getOrgId());
        }

        OrderResponseDto responseDto = orderDtoMapper.toResponseDto(savedOrder);
        return new CreateSaleResult(responseDto, result.created());
    }

    /**
     * Enqueues transactional outbox events within the current database transaction.
     * <p>
     * Note: Do NOT catch or swallow exceptions here. If the outbox enqueue fails,
     * the transaction must roll back atomically.
     * </p>
     */
    private void enqueueOutboxEvents(Order order, CreateOrderRequest request) {
        boolean settled = isActuallySettled(order);

        // 1. Asynchronous print event (if print not explicitly skipped)
        boolean skipPrint = request.getSkipAutoPrintKinds() != null && (
                settled
                        ? request.getSkipAutoPrintKinds().stream().anyMatch(k -> "BILL".equalsIgnoreCase(k) || "SETTLE".equalsIgnoreCase(k))
                        : request.getSkipAutoPrintKinds().stream().anyMatch(k -> "KOT".equalsIgnoreCase(k))
        );
        if (!skipPrint) {
            String printEventType = settled ? "ORDER_SETTLED_PRINT" : "ORDER_CONFIRMED";
            outboxService.enqueue(
                    "ORDER",
                    order.getId(),
                    printEventType,
                    order.getClientId(),
                    order.getOrgId(),
                    Map.of(
                            "orderId", order.getId().toString(),
                            "orderNo", order.getOrderNo() != null ? order.getOrderNo() : ""
                    )
            );
        }

        // 2. Asynchronous push / SSE live status update based on actual state transition
        String notificationEventType = settled ? "ORDER_SETTLED_NOTIFICATION" : "ORDER_CREATED_NOTIFICATION";
        outboxService.enqueue(
                "ORDER",
                order.getId(),
                notificationEventType,
                order.getClientId(),
                order.getOrgId(),
                Map.of(
                        "orderId", order.getId().toString(),
                        "orderNo", order.getOrderNo() != null ? order.getOrderNo() : "",
                        "orderStatus", order.getOrderStatus() != null ? order.getOrderStatus() : "COMPLETED"
                )
        );

        // 3. Asynchronous loyalty earn/redeem if order is actually settled, customer is attached,
        //    and order is NOT a credit sale (credit orders must not earn loyalty points).
        boolean isCredit = Boolean.TRUE.equals(order.getIsCredit()) || order.getCreditCustomerId() != null;
        UUID targetCustomerId = order.getCustomerId();
        if (targetCustomerId == null && order.getCustomers() != null && !order.getCustomers().isEmpty()) {
            targetCustomerId = order.getCustomers().get(0).getId();
        }

        if (settled && !isCredit && targetCustomerId != null) {
            BigDecimal eligible = orderService.computeLoyaltyEligibleAmount(order);
            Integer redeemPoints = order.getRedeemPoints() != null ? order.getRedeemPoints() : 0;
            outboxService.enqueue(
                    "ORDER",
                    order.getId(),
                    "ORDER_SETTLED",
                    order.getClientId(),
                    order.getOrgId(),
                    Map.of(
                            "orderId", order.getId().toString(),
                            "customerId", targetCustomerId.toString(),
                            "redeemPoints", redeemPoints,
                            "loyaltyEligibleAmount", eligible != null ? eligible.toPlainString() : "0"
                    )
            );
            log.info("Enqueued ORDER_SETTLED loyalty outbox event for orderId={} customerId={} eligible={}",
                    order.getId(), targetCustomerId, eligible);
        } else {
            log.info("Skipped loyalty outbox event for orderId={} | settled={} | isCredit={} | customerId={}",
                    order.getId(), settled, isCredit, targetCustomerId);
        }

        log.info("POS Sale outbox events enqueued successfully for order {} | settled={}",
                order.getId(), settled);
    }

    /**
     * Checks if the order has satisfied full completion and payment conditions.
     */
    private boolean isActuallySettled(Order order) {
        return order != null
                && "COMPLETED".equalsIgnoreCase(order.getOrderStatus())
                && ("PAID".equalsIgnoreCase(order.getPaymentStatus()) || Boolean.TRUE.equals(order.getIsCredit()));
    }

    /**
     * Registers cache invalidation to run strictly after transaction commit.
     */
    private void schedulePostCommitCacheInvalidation(UUID clientId, UUID orgId) {
        if (clientId == null) return;

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        cacheInvalidationService.invalidateProducts(clientId, orgId);
                        cacheInvalidationService.invalidateTables(clientId, orgId);
                    } catch (Exception e) {
                        log.warn("POS cache invalidation error after commit: {}", e.getMessage());
                    }
                }
            });
        } else {
            try {
                cacheInvalidationService.invalidateProducts(clientId, orgId);
                cacheInvalidationService.invalidateTables(clientId, orgId);
            } catch (Exception e) {
                log.warn("POS cache invalidation error: {}", e.getMessage());
            }
        }
    }

    /**
     * Result record for sale creation, carrying the response DTO and whether
     * the order was newly created or was a deduplicated idempotent match.
     */
    public record CreateSaleResult(OrderResponseDto order, boolean created) {}
}
