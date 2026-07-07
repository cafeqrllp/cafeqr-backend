package com.restaurant.pos.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.accounting.service.AccountingPostingService;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.idempotency.IdempotencyGuard;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.credit.domain.CreditCustomer;
import com.restaurant.pos.credit.repository.CreditCustomerRepository;
import com.restaurant.pos.inventory.service.InventoryService;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.*;
import com.restaurant.pos.order.dto.OrderCreditCompletionRequest;
import com.restaurant.pos.order.dto.OrderSettleRequest;
import com.restaurant.pos.order.exception.ConcurrentIdempotentRequestException;
import com.restaurant.pos.order.idempotency.OrderIdempotencyStore;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.order.repository.PaymentSplitRepository;
import com.restaurant.pos.print.service.PrintJobService;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.purchasing.repository.CurrencyRepository;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.service.DocumentSequenceService;
import com.restaurant.pos.sequence.service.OfflineSequenceLeaseService;
import com.restaurant.pos.table.repository.RestaurantTableRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderWorkflowParityTest {

    private OrderRepository orderRepository;
    private InvoiceRepository invoiceRepository;
    private PaymentRepository paymentRepository;
    private AccountingPostingService accountingPostingService;
    private DocumentSequenceService sequenceService;
    private CustomerRepository customerRepository;
    private CreditCustomerRepository creditCustomerRepository;
    private SystemConfigurationService configurationService;
    private OrderService orderService;

    private UUID clientId;
    private UUID orgId;

    private List<Order> mockOrders;
    private List<Invoice> mockInvoices;
    private List<Payment> mockPayments;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        invoiceRepository = mock(InvoiceRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        sequenceService = mock(DocumentSequenceService.class);
        customerRepository = mock(CustomerRepository.class);
        creditCustomerRepository = mock(CreditCustomerRepository.class);
        configurationService = mock(SystemConfigurationService.class);

        mockOrders = new ArrayList<>();
        mockInvoices = new ArrayList<>();
        mockPayments = new ArrayList<>();

        ConfigurationDto mockConfig = ConfigurationDto.builder()
                .taxEnabled(true)
                .pricesIncludeTax(false)
                .currencyDecimalPlaces(2)
                .build();
        when(configurationService.getEffectiveConfigurationForBranch(any())).thenReturn(mockConfig);

        // Simulate Order DB save & query behaviors
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            if (o.getId() == null) o.setId(UUID.randomUUID());
            mockOrders.removeIf(existing -> existing.getId().equals(o.getId()));
            mockOrders.add(o);
            return o;
        });
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            if (o.getId() == null) o.setId(UUID.randomUUID());
            mockOrders.removeIf(existing -> existing.getId().equals(o.getId()));
            mockOrders.add(o);
            return o;
        });
        when(orderRepository.findByIdAndClientIdAndOrgId(any(UUID.class), any(UUID.class), any(UUID.class))).thenAnswer(invocation -> {
            UUID oid = invocation.getArgument(0);
            return mockOrders.stream().filter(o -> oid.equals(o.getId())).findFirst();
        });

        // Simulate Invoice DB save & query behaviors
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> {
            Invoice inv = invocation.getArgument(0);
            if (inv.getId() == null) inv.setId(UUID.randomUUID());
            if (inv.getInvoiceNo() == null) inv.setInvoiceNo("INV-MOCK");
            mockInvoices.removeIf(existing -> existing.getId().equals(inv.getId()));
            mockInvoices.add(inv);
            return inv;
        });
        when(invoiceRepository.saveAndFlush(any(Invoice.class))).thenAnswer(invocation -> {
            Invoice inv = invocation.getArgument(0);
            if (inv.getId() == null) inv.setId(UUID.randomUUID());
            if (inv.getInvoiceNo() == null) inv.setInvoiceNo("INV-MOCK");
            mockInvoices.removeIf(existing -> existing.getId().equals(inv.getId()));
            mockInvoices.add(inv);
            return inv;
        });
        when(invoiceRepository.findByOrderId(any(UUID.class))).thenAnswer(invocation -> {
            UUID oid = invocation.getArgument(0);
            return mockInvoices.stream().filter(inv -> oid.equals(inv.getOrderId())).toList();
        });

        // Simulate Payment DB save & query behaviors
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            mockPayments.removeIf(existing -> existing.getId().equals(p.getId()));
            mockPayments.add(p);
            return p;
        });
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            mockPayments.removeIf(existing -> existing.getId().equals(p.getId()));
            mockPayments.add(p);
            return p;
        });
        when(paymentRepository.findByOrderId(any(UUID.class))).thenAnswer(invocation -> {
            UUID oid = invocation.getArgument(0);
            return mockPayments.stream().filter(p -> oid.equals(p.getOrderId())).toList();
        });

        accountingPostingService = mock(AccountingPostingService.class);

        com.restaurant.pos.common.context.TimezoneResolver timezoneResolver = mock(com.restaurant.pos.common.context.TimezoneResolver.class);
        when(timezoneResolver.resolveTimezone(any(), any())).thenReturn(java.time.ZoneId.of("UTC"));

        org.springframework.context.ApplicationContext applicationContext = mock(org.springframework.context.ApplicationContext.class);

        orderService = new OrderService(
                orderRepository,
                invoiceRepository,
                paymentRepository,
                mock(PaymentSplitRepository.class),
                mock(com.restaurant.pos.accounting.repository.PaymentAllocationRepository.class),
                accountingPostingService,
                mock(InventoryService.class),
                mock(RestaurantTableRepository.class),
                sequenceService,
                mock(OfflineSequenceLeaseService.class),
                mock(PrintJobService.class),
                mock(ProductRepository.class),
                customerRepository,
                creditCustomerRepository,
                configurationService,
                new ObjectMapper(),
                new com.restaurant.pos.common.service.BranchContextService(),
                mock(com.restaurant.pos.auth.repository.UserRepository.class),
                mock(com.restaurant.pos.push.service.PushNotificationService.class),
                timezoneResolver,
                mock(CurrencyRepository.class),
                new OrderCalculationService(),
                applicationContext
        );

        when(applicationContext.getBean(OrderService.class)).thenReturn(orderService);

        clientId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "staff@example.com",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ─── 1. DIRECT SETTLE WORKFLOWS ──────────────────────────────────────────

    @Test
    void directSettleMixedTaxesAndPackagedGoodsCalculatesCorrectly() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderType(OrderType.SALE)
                .orderStatus("COMPLETED")
                .paymentStatus("PAID")
                .paymentMethod("CASH")
                .build();

        // Line 1: Normal exclusive tax (100.00, 18% tax)
        OrderLine line1 = OrderLine.builder()
                .clientLineId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .productName("Exclusive Good")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(new BigDecimal("18.00"))
                .taxType(TaxType.EXCLUSIVE)
                .build();
        order.addLine(line1);

        // Line 2: Packaged good forced inclusive (118.00, 18% tax)
        OrderLine line2 = OrderLine.builder()
                .clientLineId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .productName("Packaged Item")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("118.00"))
                .taxRate(new BigDecimal("18.00"))
                .isPackagedGood(true)
                .build();
        order.addLine(line2);

        when(sequenceService.generateNextSequence(DocumentType.SALE_ORDER)).thenReturn("SO-DIRECT");
        when(sequenceService.generateNextSequence(DocumentType.CUSTOMER_INVOICE)).thenReturn("INV-DIRECT");

        Order saved = orderService.createOrder(order);

        // Gross base total: Line1 (100) + Line2 (100) = 200
        // Gross face total: Line1 (118) + Line2 (118) = 236
        assertThat(saved.getGrossAmount()).isEqualByComparingTo("236.00");
        assertThat(saved.getTotalTaxAmount()).isEqualByComparingTo("36.00");
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("236.00");
        assertThat(saved.getOrderStatus()).isEqualTo("COMPLETED");
        assertThat(saved.getPaymentStatus()).isEqualTo("PAID");

        verify(invoiceRepository, atLeastOnce()).save(any(Invoice.class));
        verify(paymentRepository, atLeastOnce()).save(any(Payment.class));
    }

    @Test
    void directSettleWith100PercentLineDiscountCalculatesFreeTotalsSafely() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderType(OrderType.SALE)
                .orderStatus("COMPLETED")
                .paymentStatus("PAID")
                .paymentMethod("UPI")
                .build();

        // Line with 100% discount
        OrderLine line = OrderLine.builder()
                .clientLineId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .productName("Free Good")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(new BigDecimal("18.00"))
                .taxType(TaxType.EXCLUSIVE)
                .manualDiscountPercent(new BigDecimal("100.00"))
                .build();
        order.addLine(line);

        when(sequenceService.generateNextSequence(DocumentType.SALE_ORDER)).thenReturn("SO-FREE");
        when(sequenceService.generateNextSequence(DocumentType.CUSTOMER_INVOICE)).thenReturn("INV-FREE");

        Order saved = orderService.createOrder(order);

        assertThat(saved.getGrossAmount()).isEqualByComparingTo("118.00");
        assertThat(saved.getLineDiscountFaceAmount()).isEqualByComparingTo("118.00");
        assertThat(saved.getTotalTaxAmount()).isEqualByComparingTo("0.00");
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("0.00");
    }

    // ─── 2. KITCHEN ORDER -> LATER SETTLE WORKFLOWS ──────────────────────────

    @Test
    void kitchenOrderLaterSettleCalculatesAndUpdatesDatabaseCorrectly() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderType(OrderType.SALE)
                .orderStatus("KITCHEN")
                .paymentStatus("PENDING")
                .build();

        OrderLine line = OrderLine.builder()
                .clientLineId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .productName("Tea")
                .quantity(BigDecimal.valueOf(2))
                .unitPrice(new BigDecimal("50.00"))
                .taxRate(new BigDecimal("5.00"))
                .taxType(TaxType.INCLUSIVE)
                .build();
        order.addLine(line);

        when(sequenceService.generateNextSequence(DocumentType.SALE_ORDER)).thenReturn("SO-KOT");

        Order savedKot = orderService.createOrder(order);
        assertThat(savedKot.getOrderStatus()).isEqualTo("KITCHEN");
        assertThat(savedKot.getGrandTotal()).isEqualByComparingTo("100.00");

        // Simulate later settle request
        when(sequenceService.generateNextSequence(DocumentType.CUSTOMER_INVOICE)).thenReturn("INV-KOT");
        when(sequenceService.generateNextSequence(DocumentType.INBOUND_PAYMENT)).thenReturn("PAY-KOT");

        OrderSettleRequest settleReq = new OrderSettleRequest();
        settleReq.setPaymentMethod("UPI");
        settleReq.setAmountPaid(new BigDecimal("100.00"));

        Order settled = orderService.settleOrder(savedKot.getId(), settleReq);

        assertThat(settled.getOrderStatus()).isEqualTo("COMPLETED");
        assertThat(settled.getPaymentStatus()).isEqualTo("PAID");
        assertThat(settled.getGrandTotal()).isEqualByComparingTo("100.00");

        verify(invoiceRepository, atLeastOnce()).save(any(Invoice.class));
        verify(paymentRepository, atLeastOnce()).save(any(Payment.class));
    }

    // ─── 3. EDITED KITCHEN ORDER -> LATER SETTLE WORKFLOWS ───────────────────

    @Test
    void kitchenOrderUpdatedThenSettledRecalculatesTotalsCorrectly() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderType(OrderType.SALE)
                .orderStatus("KITCHEN")
                .paymentStatus("PENDING")
                .build();

        UUID clientLineId = UUID.randomUUID();
        OrderLine line = OrderLine.builder()
                .clientLineId(clientLineId)
                .productId(UUID.randomUUID())
                .productName("Burger")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(new BigDecimal("5.00"))
                .taxType(TaxType.EXCLUSIVE)
                .build();
        order.addLine(line);

        when(sequenceService.generateNextSequence(DocumentType.SALE_ORDER)).thenReturn("SO-EDIT");

        Order savedKot = orderService.createOrder(order);
        assertThat(savedKot.getGrandTotal()).isEqualByComparingTo("105.00");

        // Update KOT order
        Order updates = Order.builder()
                .orderStatus("KITCHEN")
                .paymentStatus("PENDING")
                .build();
        
        OrderLine updatedLine = OrderLine.builder()
                .clientLineId(clientLineId)
                .productId(line.getProductId())
                .productName("Burger")
                .quantity(BigDecimal.valueOf(2)) // Qty updated to 2
                .unitPrice(new BigDecimal("100.00"))
                .taxRate(new BigDecimal("5.00"))
                .taxType(TaxType.EXCLUSIVE)
                .build();
        updates.addLine(updatedLine);

        Order updatedOrder = orderService.updateOrder(savedKot.getId(), updates);
        assertThat(updatedOrder.getGrandTotal()).isEqualByComparingTo("210.00");

        // Settle updated order
        when(sequenceService.generateNextSequence(DocumentType.CUSTOMER_INVOICE)).thenReturn("INV-EDIT");
        when(sequenceService.generateNextSequence(DocumentType.INBOUND_PAYMENT)).thenReturn("PAY-EDIT");

        OrderSettleRequest settleReq = new OrderSettleRequest();
        settleReq.setPaymentMethod("CARD");
        settleReq.setAmountPaid(new BigDecimal("210.00"));

        Order settled = orderService.settleOrder(updatedOrder.getId(), settleReq);
        assertThat(settled.getOrderStatus()).isEqualTo("COMPLETED");
        assertThat(settled.getPaymentStatus()).isEqualTo("PAID");
        assertThat(settled.getGrandTotal()).isEqualByComparingTo("210.00");
    }

    // ─── 4. COMPLETED ORDER REVISIONS ────────────────────────────────────────

    @Test
    void updatingCompletedOrderIncrementsRevisionAndArchivesPriorOrderAsVoid() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderNo("SO-100")
                .orderType(OrderType.SALE)
                .orderStatus("BILLED") // Must be non-completed to allow edits
                .paymentStatus("PENDING")
                .revisionNumber(0)
                .build();
        mockOrders.add(order);

        UUID clientLineId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        OrderLine line = OrderLine.builder()
                .clientLineId(clientLineId)
                .productId(productId)
                .productName("Coffee")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("50.00"))
                .build();
        order.addLine(line);

        when(sequenceService.generateNextSequence(DocumentType.SALE_ORDER)).thenReturn("SO-100");
        
        Invoice invoice = Invoice.builder().id(UUID.randomUUID()).orderId(orderId).invoiceNo("INV-100").totalAmount(new BigDecimal("50.00")).build();
        mockInvoices.add(invoice);

        // Trigger updates to modify the order
        Order updates = Order.builder()
                .orderStatus("BILLED")
                .paymentStatus("PENDING")
                .build();
        OrderLine updatedLine = OrderLine.builder()
                .clientLineId(clientLineId)
                .productId(productId)
                .productName("Coffee")
                .quantity(BigDecimal.valueOf(2)) // increase quantity
                .unitPrice(new BigDecimal("50.00"))
                .build();
        updates.addLine(updatedLine);

        Order revised = orderService.updateOrder(orderId, updates);

        // Verify revision incremented
        assertThat(revised.getRevisionNumber()).isEqualTo(1);
        assertThat(revised.getOriginalOrderId()).isEqualTo(orderId);
        
        // Verify invoice was updated or replaced via journal
        verify(accountingPostingService, times(1)).reverseInvoice(any(Invoice.class), anyString());
    }

    // ─── 5. ROUND-OFF CONFIGURATIONS ──────────────────────────────────────────

    @Test
    void roundOffDisabledDoesNotRoundTotals() {
        Order order = new Order();
        OrderLine line = OrderLine.builder()
                .clientLineId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .unitPrice(new BigDecimal("10.24"))
                .quantity(BigDecimal.ONE)
                .build();
        order.addLine(line);

        // Profile round-off config disabled
        ConfigurationDto mockConfig = ConfigurationDto.builder()
                .roundOffEnabled(false)
                .roundOffMode("AUTOMATIC")
                .build();
        when(configurationService.getEffectiveConfigurationForBranch(any())).thenReturn(mockConfig);

        Order saved = orderService.createOrder(order);
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("10.24");
    }

    @Test
    void roundOffAutomaticRoundsToAutoFactor() {
        Order order = new Order();
        OrderLine line = OrderLine.builder()
                .clientLineId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .unitPrice(new BigDecimal("10.24"))
                .quantity(BigDecimal.ONE)
                .build();
        order.addLine(line);

        // Automatic mode with factor 5 (should round 10.24 -> 10.25)
        ConfigurationDto mockConfig = ConfigurationDto.builder()
                .roundOffEnabled(true)
                .roundOffMode("AUTOMATIC")
                .roundOffAutoFactor(new BigDecimal("0.05"))
                .build();
        when(configurationService.getEffectiveConfigurationForBranch(any())).thenReturn(mockConfig);

        Order saved = orderService.createOrder(order);
        assertThat(saved.getRoundOffAmount()).isEqualByComparingTo("0.01");
        assertThat(saved.getGrandTotal()).isEqualByComparingTo("10.25");
    }

    // ─── 6. INVALID VALUE ENGINE BOUNDS ──────────────────────────────────────

    @Test
    void negativeOrderDiscountThrowsException() {
        Order order = new Order();
        order.setOrderDiscountType("PERCENT");
        order.setOrderDiscountValue(new BigDecimal("-10.00")); // negative
        order.addLine(OrderLine.builder().clientLineId(UUID.randomUUID()).productId(UUID.randomUUID()).unitPrice(BigDecimal.TEN).build());

        assertThatThrownBy(() -> orderService.createOrder(order))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Order discount value cannot be negative.");
    }

    @Test
    void orderDiscountPercentExceeding100PercentThrowsException() {
        Order order = new Order();
        order.setOrderDiscountType("PERCENT");
        order.setOrderDiscountValue(new BigDecimal("110.00")); // > 100
        order.addLine(OrderLine.builder().clientLineId(UUID.randomUUID()).productId(UUID.randomUUID()).unitPrice(BigDecimal.TEN).build());

        assertThatThrownBy(() -> orderService.createOrder(order))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Order discount percentage cannot exceed 100%.");
    }

    // ─── 7. IDEMPOTENCY & CONCURRENT REQUEST LOCKING ──────────────────────────

    @Test
    void idempotencyGuardReturnsCachedValueOnHit() {
        OrderIdempotencyStore store = mock(OrderIdempotencyStore.class);
        IdempotencyGuard guard = new IdempotencyGuard(store);

        String key = "test-key";
        UUID resourceId = UUID.randomUUID();
        String action = "settle";

        String cachedVal = "Cached Response Payload";
        when(store.get(anyString(), eq(String.class))).thenReturn(cachedVal);

        String result = guard.execute(action, resourceId, key, String.class, () -> "New Value");

        assertThat(result).isEqualTo(cachedVal);
        verify(store, never()).acquireLock(anyString(), any());
    }

    @Test
    void idempotencyGuardThrowsConcurrentRequestExceptionWhenLockAcquisitionFails() {
        OrderIdempotencyStore store = mock(OrderIdempotencyStore.class);
        IdempotencyGuard guard = new IdempotencyGuard(store);

        String key = "concurrent-key";
        UUID resourceId = UUID.randomUUID();
        String action = "bill";

        when(store.get(anyString(), eq(String.class))).thenReturn(null);
        when(store.acquireLock(anyString(), any(Duration.class))).thenReturn(false); // lock fails

        assertThatThrownBy(() -> guard.execute(action, resourceId, key, String.class, () -> "Value"))
                .isInstanceOf(ConcurrentIdempotentRequestException.class)
                .hasMessageContaining("A request with the same idempotency key is already in progress.");
    }

    @Test
    void createOrderIdempotencyChecksFingerprint() {
        UUID tenantId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        TenantContext.setCurrentTenant(tenantId);
        TenantContext.setCurrentOrg(organizationId);

        Order existingOrder = new Order();
        existingOrder.setId(UUID.randomUUID());
        existingOrder.setClientId(tenantId);
        existingOrder.setOrgId(organizationId);
        existingOrder.setSourceLocalRef("key123");
        existingOrder.setRequestFingerprint("fingerprint_hash_abc");

        when(orderRepository.findByClientIdAndOrgIdAndSourceLocalRefAndOrderStatusNot(any(), any(), eq("key123"), eq("VOID")))
                .thenReturn(Optional.of(existingOrder));

        // 1. Same key + Same fingerprint -> returns existing order
        Order duplicateRequest = new Order();
        duplicateRequest.setSourceLocalRef("key123");
        duplicateRequest.setRequestFingerprint("fingerprint_hash_abc");

        com.restaurant.pos.order.dto.IdempotentCreateResult result = orderService.createOrderIdempotently(duplicateRequest);
        assertThat(result.order().getId()).isEqualTo(existingOrder.getId());
        assertThat(result.created()).isFalse();

        // 2. Same key + Different fingerprint -> throws DuplicateResourceException
        Order conflictRequest = new Order();
        conflictRequest.setSourceLocalRef("key123");
        conflictRequest.setRequestFingerprint("fingerprint_hash_xyz");

        assertThatThrownBy(() -> orderService.createOrderIdempotently(conflictRequest))
                .isInstanceOf(com.restaurant.pos.common.exception.DuplicateResourceException.class)
                .hasMessageContaining("Idempotency conflict: a different request with the same key has already been processed.");
    }
}
