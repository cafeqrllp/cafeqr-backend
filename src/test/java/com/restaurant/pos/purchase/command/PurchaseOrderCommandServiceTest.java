package com.restaurant.pos.purchase.command;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.inventory.service.InventoryService;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.purchase.domain.PurchaseOrder;
import com.restaurant.pos.purchase.mapper.PurchaseOrderDtoMapper;
import com.restaurant.pos.sequence.service.DocumentSequenceService;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.sequence.domain.DocumentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PurchaseOrderCommandServiceTest {

    private OrderRepository orderRepository;
    private PurchaseOrderDtoMapper mapper;
    private BranchContextService branchContext;
    private DocumentSequenceService documentSequenceService;
    private PurchaseOrderCommandService service;
    private UUID clientId;
    private UUID orgId;
    private UUID vendorId;
    private UUID warehouseId;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        mapper = mock(PurchaseOrderDtoMapper.class);
        branchContext = mock(BranchContextService.class);
        documentSequenceService = mock(DocumentSequenceService.class);
        service = new PurchaseOrderCommandService(
                orderRepository,
                documentSequenceService,
                mapper,
                branchContext,
                mock(ApplicationEventPublisher.class),
                mock(InventoryService.class),
                mock(InvoiceRepository.class),
                mock(PaymentRepository.class),
                mock(PlatformTransactionManager.class)
        );
        clientId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        vendorId = UUID.randomUUID();
        warehouseId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);

        when(branchContext.requireWriteOrgId(any())).thenReturn(orgId);
        when(documentSequenceService.generateNextSequence(any(DocumentType.class), any())).thenReturn("PO-NEW");
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void receivingAnInvoiceAlreadyReceivedFromTheSameVendorIsRejected() {
        PurchaseOrder prior = purchase("PO-0001", "INV-1001", true);
        when(orderRepository.findReceivedPurchasesByVendorAndReference(
                eq(clientId), eq(orgId), eq(vendorId), eq(OrderType.PURCHASE), eq("INV-1001"), any(UUID.class)))
                .thenReturn(List.of(prior));
        when(mapper.toEntity(any(CreatePurchaseOrderCommand.class))).thenReturn(purchase(null, "INV-1001", true));

        assertThatThrownBy(() -> service.createPurchaseOrder(command(true, "INV-1001"), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("INV-1001")
                .hasMessageContaining("PO-0001")
                .hasMessageContaining("already received");
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void firstReceiptOfAnInvoiceIsAccepted() {
        when(orderRepository.findReceivedPurchasesByVendorAndReference(any(), any(), any(), any(), anyString(), any(UUID.class)))
                .thenReturn(List.of());
        when(mapper.toEntity(any(CreatePurchaseOrderCommand.class))).thenReturn(purchase(null, "INV-2002", true));

        service.createPurchaseOrder(command(true, "INV-2002"), null);

        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void draftOrdersDoNotChangeStockSoTheyAreNotChecked() {
        when(mapper.toEntity(any(CreatePurchaseOrderCommand.class))).thenReturn(purchase(null, "INV-3003", false));

        service.createPurchaseOrder(command(false, "INV-3003"), null);

        verify(orderRepository, never()).findReceivedPurchasesByVendorAndReference(any(), any(), any(), any(), anyString(), any(UUID.class));
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void receiptsWithoutAnInvoiceReferenceAreNotChecked() {
        when(mapper.toEntity(any(CreatePurchaseOrderCommand.class))).thenReturn(purchase(null, "   ", true));

        service.createPurchaseOrder(command(true, null), null);

        verify(orderRepository, never()).findReceivedPurchasesByVendorAndReference(any(), any(), any(), any(), anyString(), any(UUID.class));
        verify(orderRepository).save(any(Order.class));
    }

    private CreatePurchaseOrderCommand command(boolean received, String reference) {
        CreatePurchaseOrderCommand cmd = new CreatePurchaseOrderCommand();
        cmd.setVendorId(vendorId);
        cmd.setWarehouseId(warehouseId);
        cmd.setOrgId(orgId);
        cmd.setIsReceived(received);
        cmd.setOrderStatus(received ? "COMPLETED" : "DRAFT");
        cmd.setPaymentMethod("CASH");
        cmd.setReference(reference);
        CreatePurchaseOrderCommand.PurchaseOrderLineCommand line = new CreatePurchaseOrderCommand.PurchaseOrderLineCommand();
        line.setProductId(UUID.randomUUID());
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(BigDecimal.TEN);
        line.setTaxRate(BigDecimal.ZERO);
        cmd.setLines(List.of(line));
        return cmd;
    }

    private PurchaseOrder purchase(String orderNo, String reference, boolean received) {
        PurchaseOrder po = new PurchaseOrder();
        po.setOrderType(OrderType.PURCHASE);
        po.setOrderNo(orderNo);
        po.setVendorId(vendorId);
        po.setWarehouseId(warehouseId);
        po.setReference(reference);
        po.setIsReceived(received);
        po.setOrderStatus(received ? "COMPLETED" : "DRAFT");
        po.setPaymentMethod("CASH");
        OrderLine line = new OrderLine();
        line.setProductId(UUID.randomUUID());
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(BigDecimal.TEN);
        line.setTaxRate(BigDecimal.ZERO);
        line.setTaxAmount(BigDecimal.ZERO);
        line.setDiscountAmount(BigDecimal.ZERO);
        po.setLines(new java.util.ArrayList<>(List.of(line)));
        return po;
    }
}
