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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PosSaleCommandService}.
 *
 * Verifies outbox event enqueue logic for print, notification, and loyalty,
 * including the credit-order loyalty exclusion fix.
 */
class PosSaleCommandServiceTest {

    private OrderService orderService;
    private OrderDtoMapper orderDtoMapper;
    private OrderRequestFingerprintService fingerprintService;
    private OutboxService outboxService;
    private PosCacheInvalidationService cacheInvalidationService;
    private PosSaleCommandService service;

    private UUID clientId;
    private UUID orgId;
    private UUID orderId;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        orderDtoMapper = mock(OrderDtoMapper.class);
        fingerprintService = mock(OrderRequestFingerprintService.class);
        outboxService = mock(OutboxService.class);
        cacheInvalidationService = mock(PosCacheInvalidationService.class);

        service = new PosSaleCommandService(
                orderService, orderDtoMapper, fingerprintService,
                outboxService, cacheInvalidationService
        );

        clientId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        customerId = UUID.randomUUID();

        when(fingerprintService.fingerprint(any())).thenReturn("fp-test");
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private Order buildOrder(String orderStatus, String paymentStatus,
                             UUID customerId, Boolean isCredit, UUID creditCustomerId) {
        Order order = new Order();
        order.setId(orderId);
        order.setClientId(clientId);
        order.setOrgId(orgId);
        order.setOrderNo("ORD-001");
        order.setOrderStatus(orderStatus);
        order.setPaymentStatus(paymentStatus);
        order.setCustomerId(customerId);
        order.setIsCredit(isCredit);
        order.setCreditCustomerId(creditCustomerId);
        order.setRedeemPoints(null);
        return order;
    }

    private CreateOrderRequest buildRequest() {
        return buildRequest(null);
    }

    private CreateOrderRequest buildRequest(List<String> skipAutoPrintKinds) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setSourceLocalRef("idem-key-1");
        req.setSkipAutoPrintKinds(skipAutoPrintKinds);
        return req;
    }

    private void stubCreateOrderReturning(Order order) {
        when(orderDtoMapper.toEntity(any(CreateOrderRequest.class))).thenReturn(order);
        when(orderService.createOrderIdempotently(any()))
                .thenReturn(new IdempotentCreateResult(order, true));
        when(orderDtoMapper.toResponseDto(any()))
                .thenReturn(OrderResponseDto.builder().id(order.getId()).build());
    }

    private void stubIdempotentDuplicate(Order existingOrder) {
        when(orderDtoMapper.toEntity(any(CreateOrderRequest.class))).thenReturn(existingOrder);
        when(orderService.createOrderIdempotently(any()))
                .thenReturn(new IdempotentCreateResult(existingOrder, false));
        when(orderDtoMapper.toResponseDto(any()))
                .thenReturn(OrderResponseDto.builder().id(existingOrder.getId()).build());
    }

    // ─── Loyalty: Settled PAID + Customer → enqueue ORDER_SETTLED ───────

    @Test
    void settledPaidOrderWithCustomer_enqueueLoyaltyEvent() {
        Order order = buildOrder("COMPLETED", "PAID", customerId, false, null);
        stubCreateOrderReturning(order);
        when(orderService.computeLoyaltyEligibleAmount(order)).thenReturn(new BigDecimal("500.00"));

        service.createSaleOrder(buildRequest(), "idem-key-1");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);

        // 3 events: print, notification, loyalty
        verify(outboxService, times(3)).enqueue(
                eq("ORDER"), eq(orderId), eventTypeCaptor.capture(),
                eq(clientId), eq(orgId), payloadCaptor.capture()
        );

        List<String> eventTypes = eventTypeCaptor.getAllValues();
        assertThat(eventTypes).contains("ORDER_SETTLED");

        // Verify loyalty payload
        int loyaltyIdx = eventTypes.indexOf("ORDER_SETTLED");
        Map<String, Object> loyaltyPayload = payloadCaptor.getAllValues().get(loyaltyIdx);
        assertThat(loyaltyPayload.get("customerId")).isEqualTo(customerId.toString());
        assertThat(loyaltyPayload.get("loyaltyEligibleAmount")).isEqualTo("500.00");
    }

    // ─── Loyalty: Credit order with isCredit=true → NO loyalty ──────────

    @Test
    void creditOrderWithIsCreditTrue_doesNotEnqueueLoyaltyEvent() {
        Order order = buildOrder("COMPLETED", "PAID", customerId, true, null);
        stubCreateOrderReturning(order);

        service.createSaleOrder(buildRequest(), "idem-key-1");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService, atLeastOnce()).enqueue(
                eq("ORDER"), eq(orderId), eventTypeCaptor.capture(),
                eq(clientId), eq(orgId), any()
        );

        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("ORDER_SETTLED");
    }

    // ─── Loyalty: Credit order with creditCustomerId → NO loyalty ───────

    @Test
    void creditOrderWithCreditCustomerId_doesNotEnqueueLoyaltyEvent() {
        UUID creditCustId = UUID.randomUUID();
        Order order = buildOrder("COMPLETED", "PAID", customerId, false, creditCustId);
        stubCreateOrderReturning(order);

        service.createSaleOrder(buildRequest(), "idem-key-1");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService, atLeastOnce()).enqueue(
                eq("ORDER"), eq(orderId), eventTypeCaptor.capture(),
                eq(clientId), eq(orgId), any()
        );

        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("ORDER_SETTLED");
    }

    // ─── Loyalty: Credit order with both flags → NO loyalty ─────────────

    @Test
    void creditOrderWithBothFlags_doesNotEnqueueLoyaltyEvent() {
        UUID creditCustId = UUID.randomUUID();
        Order order = buildOrder("COMPLETED", "PAID", customerId, true, creditCustId);
        stubCreateOrderReturning(order);

        service.createSaleOrder(buildRequest(), "idem-key-1");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService, atLeastOnce()).enqueue(
                eq("ORDER"), eq(orderId), eventTypeCaptor.capture(),
                eq(clientId), eq(orgId), any()
        );

        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("ORDER_SETTLED");
    }

    // ─── Loyalty: Kitchen order (not settled) → NO loyalty ──────────────

    @Test
    void kitchenOrder_doesNotEnqueueLoyaltyEvent() {
        Order order = buildOrder("KITCHEN", "PENDING", customerId, false, null);
        stubCreateOrderReturning(order);

        service.createSaleOrder(buildRequest(), "idem-key-1");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService, atLeastOnce()).enqueue(
                eq("ORDER"), eq(orderId), eventTypeCaptor.capture(),
                eq(clientId), eq(orgId), any()
        );

        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("ORDER_SETTLED");
    }

    // ─── Loyalty: Settled but no customer → NO loyalty ──────────────────

    @Test
    void settledPaidOrderWithoutCustomer_doesNotEnqueueLoyaltyEvent() {
        Order order = buildOrder("COMPLETED", "PAID", null, false, null);
        stubCreateOrderReturning(order);

        service.createSaleOrder(buildRequest(), "idem-key-1");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService, atLeastOnce()).enqueue(
                eq("ORDER"), eq(orderId), eventTypeCaptor.capture(),
                eq(clientId), eq(orgId), any()
        );

        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("ORDER_SETTLED");
    }

    // ─── Print: Settled order enqueues ORDER_SETTLED_PRINT ───────────────

    @Test
    void settledOrder_enqueuesPrintEvent() {
        Order order = buildOrder("COMPLETED", "PAID", null, false, null);
        stubCreateOrderReturning(order);

        service.createSaleOrder(buildRequest(), "idem-key-1");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService, atLeastOnce()).enqueue(
                eq("ORDER"), eq(orderId), eventTypeCaptor.capture(),
                eq(clientId), eq(orgId), any()
        );

        assertThat(eventTypeCaptor.getAllValues()).contains("ORDER_SETTLED_PRINT");
    }

    // ─── Print: Kitchen order enqueues ORDER_CONFIRMED ───────────────────

    @Test
    void kitchenOrder_enqueuesConfirmedPrintEvent() {
        Order order = buildOrder("KITCHEN", "PENDING", null, false, null);
        stubCreateOrderReturning(order);

        service.createSaleOrder(buildRequest(), "idem-key-1");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService, atLeastOnce()).enqueue(
                eq("ORDER"), eq(orderId), eventTypeCaptor.capture(),
                eq(clientId), eq(orgId), any()
        );

        assertThat(eventTypeCaptor.getAllValues()).contains("ORDER_CONFIRMED");
        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("ORDER_SETTLED_PRINT");
    }

    // ─── Print: Kitchen order with skipAutoPrintKinds=['KOT'] → no print outbox event ──

    @Test
    void kitchenOrderWithSkipKot_doesNotEnqueuePrintEvent() {
        Order order = buildOrder("KITCHEN", "PENDING", null, false, null);
        stubCreateOrderReturning(order);

        service.createSaleOrder(buildRequest(List.of("KOT")), "idem-key-1");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService, atLeastOnce()).enqueue(
                eq("ORDER"), eq(orderId), eventTypeCaptor.capture(),
                eq(clientId), eq(orgId), any()
        );

        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("ORDER_CONFIRMED");
        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("ORDER_SETTLED_PRINT");
    }

    // ─── Print: skipAutoPrintKinds=['BILL'] → no print outbox event ─────

    @Test
    void settledOrderWithSkipBill_doesNotEnqueuePrintEvent() {
        Order order = buildOrder("COMPLETED", "PAID", null, false, null);
        stubCreateOrderReturning(order);

        service.createSaleOrder(buildRequest(List.of("BILL")), "idem-key-1");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService, atLeastOnce()).enqueue(
                eq("ORDER"), eq(orderId), eventTypeCaptor.capture(),
                eq(clientId), eq(orgId), any()
        );

        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("ORDER_SETTLED_PRINT");
        assertThat(eventTypeCaptor.getAllValues()).doesNotContain("ORDER_CONFIRMED");
    }

    // ─── Notification: Settled order → ORDER_SETTLED_NOTIFICATION ────────

    @Test
    void settledOrder_enqueuesSettledNotification() {
        Order order = buildOrder("COMPLETED", "PAID", null, false, null);
        stubCreateOrderReturning(order);

        service.createSaleOrder(buildRequest(), "idem-key-1");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService, atLeastOnce()).enqueue(
                eq("ORDER"), eq(orderId), eventTypeCaptor.capture(),
                eq(clientId), eq(orgId), any()
        );

        assertThat(eventTypeCaptor.getAllValues()).contains("ORDER_SETTLED_NOTIFICATION");
    }

    // ─── Notification: Kitchen order → ORDER_CREATED_NOTIFICATION ───────

    @Test
    void kitchenOrder_enqueuesCreatedNotification() {
        Order order = buildOrder("KITCHEN", "PENDING", null, false, null);
        stubCreateOrderReturning(order);

        service.createSaleOrder(buildRequest(), "idem-key-1");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService, atLeastOnce()).enqueue(
                eq("ORDER"), eq(orderId), eventTypeCaptor.capture(),
                eq(clientId), eq(orgId), any()
        );

        assertThat(eventTypeCaptor.getAllValues()).contains("ORDER_CREATED_NOTIFICATION");
    }

    // ─── Idempotent duplicate → no outbox events ────────────────────────

    @Test
    void idempotentDuplicate_doesNotEnqueueAnyEvents() {
        Order order = buildOrder("COMPLETED", "PAID", customerId, false, null);
        stubIdempotentDuplicate(order);

        PosSaleCommandService.CreateSaleResult result =
                service.createSaleOrder(buildRequest(), "idem-key-1");

        assertThat(result.created()).isFalse();
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    // ─── Loyalty: Settled order with redeemPoints → included in payload ──

    @Test
    void settledOrderWithRedeemPoints_includesRedeemInLoyaltyPayload() {
        Order order = buildOrder("COMPLETED", "PAID", customerId, false, null);
        order.setRedeemPoints(150);
        stubCreateOrderReturning(order);
        when(orderService.computeLoyaltyEligibleAmount(order)).thenReturn(new BigDecimal("300.00"));

        service.createSaleOrder(buildRequest(), "idem-key-1");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);

        verify(outboxService, atLeastOnce()).enqueue(
                eq("ORDER"), eq(orderId), eventTypeCaptor.capture(),
                eq(clientId), eq(orgId), payloadCaptor.capture()
        );

        int loyaltyIdx = eventTypeCaptor.getAllValues().indexOf("ORDER_SETTLED");
        assertThat(loyaltyIdx).isGreaterThanOrEqualTo(0);

        Map<String, Object> payload = payloadCaptor.getAllValues().get(loyaltyIdx);
        assertThat(payload.get("redeemPoints")).isEqualTo(150);
        assertThat(payload.get("loyaltyEligibleAmount")).isEqualTo("300.00");
    }

    // ─── Credit order (isCredit=true) still gets print + notification ───

    @Test
    void creditOrder_stillEnqueuesPrintAndNotification() {
        Order order = buildOrder("COMPLETED", "PAID", customerId, true, null);
        stubCreateOrderReturning(order);

        service.createSaleOrder(buildRequest(), "idem-key-1");

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService, atLeastOnce()).enqueue(
                eq("ORDER"), eq(orderId), eventTypeCaptor.capture(),
                eq(clientId), eq(orgId), any()
        );

        List<String> types = eventTypeCaptor.getAllValues();
        // Credit order SHOULD get print and notification
        assertThat(types).contains("ORDER_SETTLED_PRINT");
        assertThat(types).contains("ORDER_SETTLED_NOTIFICATION");
        // Credit order should NOT get loyalty
        assertThat(types).doesNotContain("ORDER_SETTLED");
    }
}
