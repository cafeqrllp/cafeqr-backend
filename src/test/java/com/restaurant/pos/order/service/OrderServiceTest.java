package com.restaurant.pos.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.accounting.service.AccountingPostingService;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.credit.domain.CreditCustomer;
import com.restaurant.pos.credit.repository.CreditCustomerRepository;
import com.restaurant.pos.inventory.service.InventoryService;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.domain.PaymentType;
import com.restaurant.pos.order.dto.OrderCancelRequest;
import com.restaurant.pos.order.dto.OrderSettleRequest;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.order.repository.PaymentSplitRepository;
import com.restaurant.pos.print.service.PrintJobService;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.purchasing.domain.Customer;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.service.DocumentSequenceService;
import com.restaurant.pos.sequence.service.OfflineSequenceLeaseService;
import com.restaurant.pos.table.repository.RestaurantTableRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

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

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        invoiceRepository = mock(InvoiceRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        sequenceService = mock(DocumentSequenceService.class);
        customerRepository = mock(CustomerRepository.class);
        creditCustomerRepository = mock(CreditCustomerRepository.class);
        configurationService = mock(SystemConfigurationService.class);

        accountingPostingService = mock(AccountingPostingService.class);

        orderService = new OrderService(
                orderRepository,
                invoiceRepository,
                paymentRepository,
                mock(PaymentSplitRepository.class),
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
                new com.restaurant.pos.common.service.BranchContextService()
        );

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

    @Test
    void superAdminSelectedBranchScopesLiveSalesOrders() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "owner@example.com",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        ));
        when(orderRepository.findLiveOrders(eq(clientId), eq(orgId), eq(OrderType.SALE), any()))
                .thenReturn(List.of());

        orderService.getLiveSalesOrders();

        verify(orderRepository).findLiveOrders(eq(clientId), eq(orgId), eq(OrderType.SALE), any());
    }

    @Test
    void superAdminAllBranchesLeavesLiveSalesOrdersUnscoped() {
        TenantContext.setCurrentOrg(null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "owner@example.com",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        ));
        when(orderRepository.findLiveOrders(eq(clientId), isNull(), eq(OrderType.SALE), any()))
                .thenReturn(List.of());

        orderService.getLiveSalesOrders();

        verify(orderRepository).findLiveOrders(eq(clientId), isNull(), eq(OrderType.SALE), any());
    }

    @Test
    void superAdminSelectedBranchScopesSalesHistorySearches() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "owner@example.com",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        ));
        when(orderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        when(customerRepository.findIdsByClientAndOrgAndSearch(eq(clientId), eq(orgId), eq("%inv-1%")))
                .thenReturn(List.of());
        when(customerRepository.findLinkedOrderIdsByClientAndOrgAndCustomerSearch(eq(clientId), eq(orgId), eq("%inv-1%")))
                .thenReturn(List.of());

        orderService.getSalesOrderHistory(
                Instant.parse("2026-05-23T00:00:00Z"),
                Instant.parse("2026-05-23T23:59:59Z"),
                0,
                20,
                "INV-1"
        );

        verify(customerRepository).findIdsByClientAndOrgAndSearch(eq(clientId), eq(orgId), eq("%inv-1%"));
        verify(customerRepository).findLinkedOrderIdsByClientAndOrgAndCustomerSearch(eq(clientId), eq(orgId), eq("%inv-1%"));
        capturedSalesHistorySpecs(2).forEach(spec -> assertSpecificationFiltersOrg(spec, orgId));
    }

    @Test
    void superAdminAllBranchesLeavesSalesHistoryUnscoped() {
        TenantContext.setCurrentOrg(null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "owner@example.com",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        ));
        when(orderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        when(customerRepository.findIdsByClientAndOrgAndSearch(eq(clientId), isNull(), eq("%inv-1%")))
                .thenReturn(List.of());
        when(customerRepository.findLinkedOrderIdsByClientAndOrgAndCustomerSearch(eq(clientId), isNull(), eq("%inv-1%")))
                .thenReturn(List.of());

        orderService.getSalesOrderHistory(
                Instant.parse("2026-05-23T00:00:00Z"),
                Instant.parse("2026-05-23T23:59:59Z"),
                0,
                20,
                "INV-1"
        );

        verify(customerRepository).findIdsByClientAndOrgAndSearch(eq(clientId), isNull(), eq("%inv-1%"));
        verify(customerRepository).findLinkedOrderIdsByClientAndOrgAndCustomerSearch(eq(clientId), isNull(), eq("%inv-1%"));
        capturedSalesHistorySpecs(2).forEach(this::assertSpecificationDoesNotFilterOrg);
    }

    @Test
    void staffSalesHistoryUsesAssignedBranchScope() {
        when(orderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        orderService.getSalesOrderHistory(
                Instant.parse("2026-05-23T00:00:00Z"),
                Instant.parse("2026-05-23T23:59:59Z"),
                0,
                20,
                null
        );

        capturedSalesHistorySpecs(1).forEach(spec -> assertSpecificationFiltersOrg(spec, orgId));
    }

    @Test
    void salesHistoryAllowsRangesLongerThanThirtyOneDays() {
        when(orderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        orderService.getSalesOrderHistory(
                Instant.parse("2025-12-31T18:30:00Z"),
                Instant.parse("2026-05-27T18:29:00Z"),
                0,
                20,
                null
        );

        verify(orderRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void createOrderStoresMultipleCustomerLinksOnCustomersTable() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customerAId = UUID.randomUUID();
        UUID customerBId = UUID.randomUUID();

        Customer customerA = Customer.builder().id(customerAId).name("Rahul").phone("123456").build();
        customerA.setClientId(clientId);
        customerA.setOrgId(orgId);
        Customer customerB = Customer.builder().id(customerBId).name("Riyas").phone("7012120844").build();
        customerB.setClientId(clientId);
        customerB.setOrgId(orgId);

        Order order = Order.builder()
                .id(orderId)
                .orderStatus("KITCHEN")
                .paymentStatus("PENDING")
                .customerIds(new ObjectMapper().readTree("""
                        [
                          {"id":"%s","name":"Rahul","phone":"123456"},
                          {"id":"%s","name":"Riyas","phone":"7012120844"}
                        ]
                        """.formatted(customerAId, customerBId)))
                .build();

        when(sequenceService.generateNextSequence(DocumentType.SALE_ORDER)).thenReturn("SO-1");
        when(customerRepository.findByIdAndClientId(customerAId, clientId)).thenReturn(Optional.of(customerA));
        when(customerRepository.findByIdAndClientId(customerBId, clientId)).thenReturn(Optional.of(customerB));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(customerRepository.findByClientIdAndOrderLink(eq(clientId), any(), any()))
                .thenReturn(List.of(customerA, customerB));

        Order saved = orderService.createOrder(order);

        assertThat(saved.getCustomerId()).isEqualTo(customerAId);
        assertThat(saved.getCustomers()).extracting("id").containsExactly(customerAId, customerBId);
        assertThat(customerA.getOrderLinks()).singleElement().satisfies(link -> {
            assertThat(link.getOrderId()).isEqualTo(orderId);
            assertThat(link.getIsPrimary()).isTrue();
        });
        assertThat(customerB.getOrderLinks()).singleElement().satisfies(link -> {
            assertThat(link.getOrderId()).isEqualTo(orderId);
            assertThat(link.getIsPrimary()).isFalse();
        });
    }

    @Test
    void createCreditOrderLinksCustomerAfterOrderIdIsGenerated() {
        UUID generatedOrderId = UUID.randomUUID();
        UUID linkedCustomerId = UUID.randomUUID();
        UUID creditCustomerId = UUID.randomUUID();

        Customer linkedCustomer = Customer.builder()
                .id(linkedCustomerId)
                .name("Ravi")
                .phone("1234567890")
                .build();
        linkedCustomer.setClientId(clientId);
        linkedCustomer.setOrgId(orgId);

        CreditCustomer creditCustomer = CreditCustomer.builder()
                .id(creditCustomerId)
                .linkedCustomerId(linkedCustomerId)
                .name("Ravi")
                .phone("1234567890")
                .status("ACTIVE")
                .isactive("Y")
                .build();
        creditCustomer.setClientId(clientId);
        creditCustomer.setOrgId(orgId);

        Order order = Order.builder()
                .orderStatus("COMPLETED")
                .paymentStatus("PENDING")
                .isCredit(true)
                .creditCustomerId(creditCustomerId)
                .grandTotal(new BigDecimal("118.00"))
                .totalTaxAmount(new BigDecimal("18.00"))
                .build();

        when(sequenceService.generateNextSequence(DocumentType.SALE_ORDER)).thenReturn("SO-CREDIT");
        when(sequenceService.generateNextSequence(DocumentType.CUSTOMER_INVOICE)).thenReturn("INV-CREDIT");
        when(configurationService.getConfiguration()).thenReturn(ConfigurationDto.builder().creditEnabled(true).build());
        when(creditCustomerRepository.findByIdAndClientId(creditCustomerId, clientId)).thenReturn(Optional.of(creditCustomer));
        when(customerRepository.findByIdAndClientId(linkedCustomerId, clientId)).thenReturn(Optional.of(linkedCustomer));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(generatedOrderId);
            }
            return saved;
        });
        when(invoiceRepository.findByOrderId(generatedOrderId)).thenReturn(List.of());
        when(customerRepository.findByClientIdAndOrderLink(eq(clientId), any(), any()))
                .thenReturn(List.of(linkedCustomer));

        Order saved = orderService.createOrder(order);

        assertThat(saved.getId()).isEqualTo(generatedOrderId);
        assertThat(saved.getCustomerId()).isEqualTo(linkedCustomerId);
        assertThat(saved.getCustomerName()).isEqualTo("Ravi");
        assertThat(saved.getIsCredit()).isTrue();
        assertThat(saved.getCreditCustomerId()).isEqualTo(creditCustomerId);
        assertThat(linkedCustomer.getOrderLinks()).singleElement().satisfies(link -> {
            assertThat(link.getOrderId()).isEqualTo(generatedOrderId);
            assertThat(link.getIsPrimary()).isTrue();
        });
    }

    @Test
    void settleOrderWithDiscountReplacesInvoiceJournalForCorrectedInvoiceAmount() {
        UUID orderId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();

        Order order = Order.builder()
                .id(orderId)
                .orderNo("SO-1")
                .orderType(OrderType.SALE)
                .orderStatus("BILLED")
                .paymentStatus("PENDING")
                .grandTotal(new BigDecimal("118.00"))
                .totalTaxAmount(new BigDecimal("18.00"))
                .build();
        order.setClientId(clientId);
        order.setOrgId(orgId);

        Invoice invoice = Invoice.builder()
                .id(invoiceId)
                .orderId(orderId)
                .invoiceNo("INV-1")
                .status("UNPAID")
                .docStatus("COMPLETED")
                .isPaid(false)
                .totalAmount(new BigDecimal("118.00"))
                .amountDue(new BigDecimal("118.00"))
                .build();
        invoice.setClientId(clientId);
        invoice.setOrgId(orgId);

        when(orderRepository.findByIdAndClientIdAndOrgId(orderId, clientId, orgId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceRepository.findByOrderId(orderId)).thenReturn(List.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sequenceService.generateNextSequence(DocumentType.INBOUND_PAYMENT)).thenReturn("PAY-1");
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(customerRepository.findByClientIdAndOrderLink(eq(clientId), any(), any())).thenReturn(List.of());

        OrderSettleRequest request = new OrderSettleRequest();
        request.setPaymentMethod("CASH");
        request.setDiscountAmount(new BigDecimal("50.00"));
        request.setAmountPaid(new BigDecimal("68.00"));

        Order settled = orderService.settleOrder(orderId, request);

        assertThat(settled.getGrandTotal()).isEqualByComparingTo("68.00");
        assertThat(settled.getTotalDiscountAmount()).isEqualByComparingTo("50.00");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("68.00");
        verify(accountingPostingService).replaceInvoiceJournal(order, invoice, "Invoice amount corrected after discount/roundoff");
        verify(accountingPostingService, never()).reverseInvoice(eq(invoice), eq("Invoice amount corrected after discount/roundoff"));
    }

    @Test
    void cancelPaidSaleVoidsInvoicePaymentAndReversesAccountingChain() {
        UUID orderId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        Order order = Order.builder()
                .id(orderId)
                .orderNo("SO-1")
                .orderType(OrderType.SALE)
                .orderStatus("COMPLETED")
                .paymentStatus("PAID")
                .build();
        order.setClientId(clientId);
        order.setOrgId(orgId);

        Invoice invoice = Invoice.builder()
                .id(invoiceId)
                .orderId(orderId)
                .invoiceNo("INV-1")
                .status("PAID")
                .docStatus("COMPLETED")
                .isPaid(true)
                .totalAmount(new java.math.BigDecimal("118.00"))
                .amountDue(java.math.BigDecimal.ZERO)
                .build();
        invoice.setClientId(clientId);
        invoice.setOrgId(orgId);

        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(orderId)
                .paymentType(PaymentType.INBOUND)
                .paymentMethod("CASH")
                .amountPaid(new java.math.BigDecimal("118.00"))
                .docStatus("COMPLETED")
                .isactive("Y")
                .build();
        payment.setClientId(clientId);
        payment.setOrgId(orgId);

        when(orderRepository.findByIdAndClientIdAndOrgId(orderId, clientId, orgId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceRepository.findByOrderId(orderId)).thenReturn(List.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRepository.findByOrderId(orderId)).thenReturn(List.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(customerRepository.findByClientIdAndOrderLink(eq(clientId), any(), any())).thenReturn(List.of());

        OrderCancelRequest request = new OrderCancelRequest();
        request.setReason("Wrong sale");

        Order cancelled = orderService.cancelOrder(orderId, request);

        assertThat(cancelled.getOrderStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getPaymentStatus()).isEqualTo("VOID");
        assertThat(invoice.getStatus()).isEqualTo("VOID");
        assertThat(invoice.getDocStatus()).isEqualTo("VOIDED");
        assertThat(invoice.getIsPaid()).isFalse();
        assertThat(invoice.getAmountDue()).isEqualByComparingTo("0.00");
        assertThat(payment.getDocStatus()).isEqualTo("VOIDED");
        assertThat(payment.getIsactive()).isEqualTo("N");
        verify(accountingPostingService).reverseInvoice(invoice, "Order cancelled");
        verify(accountingPostingService).reversePayment(payment, "Order cancelled");
        verify(accountingPostingService).reverseSaleCogs(order, "Order cancelled");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Specification<Order>> capturedSalesHistorySpecs(int count) {
        ArgumentCaptor<Specification<Order>> specCaptor = ArgumentCaptor.forClass((Class) Specification.class);
        verify(orderRepository, times(count)).findAll(specCaptor.capture(), any(Pageable.class));
        return specCaptor.getAllValues();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertSpecificationFiltersOrg(Specification<Order> spec, UUID expectedOrgId) {
        CriteriaProbe probe = criteriaProbe();

        spec.toPredicate(probe.root, probe.query, probe.cb);

        verify(probe.cb).equal(any(Expression.class), eq(expectedOrgId));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertSpecificationDoesNotFilterOrg(Specification<Order> spec) {
        CriteriaProbe probe = criteriaProbe();

        spec.toPredicate(probe.root, probe.query, probe.cb);

        verify(probe.root, never()).get("orgId");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CriteriaProbe criteriaProbe() {
        Root<Order> root = mock(Root.class);
        CriteriaQuery query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path path = mock(Path.class);
        Expression<String> loweredExpression = mock(Expression.class);
        Predicate predicate = mock(Predicate.class);

        when(root.get(anyString())).thenReturn(path);
        when(cb.equal(any(Expression.class), any())).thenReturn(predicate);
        when(cb.notEqual(any(Expression.class), any())).thenReturn(predicate);
        when(cb.greaterThanOrEqualTo(any(Expression.class), any(Instant.class))).thenReturn(predicate);
        when(cb.lessThanOrEqualTo(any(Expression.class), any(Instant.class))).thenReturn(predicate);
        when(cb.lower(any(Expression.class))).thenReturn(loweredExpression);
        when(cb.like(any(Expression.class), anyString(), eq('\\'))).thenReturn(predicate);
        when(cb.or(any(Predicate[].class))).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);

        return new CriteriaProbe(root, query, cb);
    }

    private record CriteriaProbe(Root<Order> root, CriteriaQuery query, CriteriaBuilder cb) {}
}
