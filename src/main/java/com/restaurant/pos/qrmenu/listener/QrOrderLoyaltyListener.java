package com.restaurant.pos.qrmenu.listener;

import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.outbox.service.OutboxService;
import com.restaurant.pos.qrmenu.event.QrOrderPlacedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

/**
 * Event listener that handles loyalty processing for QR orders when the loyalty module is enabled.
 * If customer is identified and order is paid/settled, triggers loyalty points earn.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QrOrderLoyaltyListener {

    private final SystemConfigurationService systemConfigurationService;
    private final OutboxService outboxService;

    @EventListener
    public void onQrOrderPlaced(QrOrderPlacedEvent event) {
        Order order = event.getOrder();
        UUID clientId = event.getClientId();
        UUID orgId = event.getOrgId();
        UUID customerId = event.getCustomerId() != null ? event.getCustomerId() : (order != null ? order.getCustomerId() : null);

        if (order == null) return;

        try {
            ConfigurationDto config = systemConfigurationService.getConfigurationForClientAndBranch(clientId, orgId);
            // Same default as LoyaltyCommandService / OrderService: no config row = default settings (enabled)
            boolean loyaltyEnabled = config == null || config.isLoyaltyEnabled();

            if (!loyaltyEnabled) {
                log.debug("Loyalty is disabled for client={} org={}, skipping loyalty processing for QR order {}",
                        clientId, orgId, order.getId());
                return;
            }

            if (customerId == null) {
                log.debug("No customer attached to QR order {}, skipping loyalty points processing", order.getId());
                return;
            }

            // Only completed, paid SALE orders earn points (never purchases, returns or credit)
            boolean isSale = order.getOrderType() == null || order.getOrderType() == OrderType.SALE;
            boolean isSettled = "COMPLETED".equalsIgnoreCase(order.getOrderStatus())
                    && "PAID".equalsIgnoreCase(order.getPaymentStatus());
            boolean isCredit = Boolean.TRUE.equals(order.getIsCredit()) || order.getCreditCustomerId() != null;

            if (isSale && isSettled && !isCredit) {
                BigDecimal eligible = computeLoyaltyEligibleAmount(order);
                log.info("EventListener: QR order settled with customer={} eligibleAmount={}. Enqueuing ORDER_SETTLED.",
                        customerId, eligible);

                // Single path: the transactional outbox (LoyaltyEventConsumer earns after commit,
                // with retries). No parallel in-process event, so points cannot be double-awarded.
                outboxService.enqueue(
                        "ORDER",
                        order.getId(),
                        "ORDER_SETTLED",
                        clientId,
                        orgId,
                        Map.of(
                                "orderId", order.getId().toString(),
                                "customerId", customerId.toString(),
                                "loyaltyEligibleAmount", eligible.toPlainString()
                        )
                );
            }
        } catch (Exception ex) {
            log.error("EventListener: Failed to process loyalty for QR order {}: {}", order.getId(), ex.getMessage(), ex);
        }
    }

    private BigDecimal computeLoyaltyEligibleAmount(Order order) {
        if (order == null) return BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        if (order.getLines() != null && !order.getLines().isEmpty()) {
            gross = order.getLines().stream()
                    .filter(l -> l.getLineTotal() != null)
                    .map(OrderLine::getLineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } else if (order.getTotalAmount() != null) {
            gross = order.getTotalAmount();
        }

        BigDecimal orderDisc = BigDecimal.ZERO;
        if (order.getOrderDiscountValue() != null) {
            if ("PERCENT".equalsIgnoreCase(order.getOrderDiscountType())) {
                orderDisc = gross.multiply(order.getOrderDiscountValue()
                        .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
            } else {
                orderDisc = order.getOrderDiscountValue();
            }
        }
        BigDecimal eligible = gross.subtract(orderDisc);
        return eligible.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : eligible;
    }
}
