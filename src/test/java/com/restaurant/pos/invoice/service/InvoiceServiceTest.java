package com.restaurant.pos.invoice.service;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvoiceServiceTest {

    private InvoiceRepository invoiceRepository;
    private OrderService orderService;
    private com.restaurant.pos.common.context.TimezoneResolver timezoneResolver;
    private InvoiceService invoiceService;
    private UUID clientId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        invoiceRepository = mock(InvoiceRepository.class);
        orderService = mock(OrderService.class);
        timezoneResolver = mock(com.restaurant.pos.common.context.TimezoneResolver.class);
        when(timezoneResolver.resolveTimezone(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.time.ZoneId.of("UTC"));
        invoiceService = new InvoiceService(invoiceRepository, orderService, timezoneResolver);

        clientId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "staff@example.com",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_STAFF"))
        ));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void getInvoiceResolvesToActiveCounterpartWhenVoided() {
        UUID voidedInvoiceId = UUID.randomUUID();
        UUID activeInvoiceId = UUID.randomUUID();
        String invoiceNo = "INV-001";

        Invoice voidedInvoice = Invoice.builder()
                .id(voidedInvoiceId)
                .invoiceNo(invoiceNo + "_VOID_0")
                .status("VOID")
                .isactive("N")
                .totalAmount(BigDecimal.TEN)
                .amountDue(BigDecimal.TEN)
                .build();
        voidedInvoice.setClientId(clientId);
        voidedInvoice.setOrgId(orgId);

        Invoice activeInvoice = Invoice.builder()
                .id(activeInvoiceId)
                .invoiceNo(invoiceNo)
                .status("UNPAID")
                .isactive("Y")
                .totalAmount(BigDecimal.TEN)
                .amountDue(BigDecimal.TEN)
                .build();
        activeInvoice.setClientId(clientId);
        activeInvoice.setOrgId(orgId);

        when(invoiceRepository.findByIdAndClientIdAndOrgId(eq(voidedInvoiceId), eq(clientId), eq(orgId)))
                .thenReturn(Optional.of(voidedInvoice));
        when(invoiceRepository.findActiveByInvoiceNoAndClientIdAndOrgId(eq(invoiceNo), eq(clientId), eq(orgId)))
                .thenReturn(Optional.of(activeInvoice));

        Invoice result = invoiceService.getInvoice(voidedInvoiceId);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(activeInvoiceId);
        assertThat(result.getInvoiceNo()).isEqualTo(invoiceNo);
        assertThat(result.getStatus()).isEqualTo("UNPAID");
    }

    @Test
    void getInvoiceByOrderResolvesActiveOrderAndActiveInvoice() {
        UUID voidedOrderId = UUID.randomUUID();
        UUID activeOrderId = UUID.randomUUID();
        UUID activeInvoiceId = UUID.randomUUID();
        String orderNo = "ORD-001";
        String invoiceNo = "INV-001";

        Order activeOrder = Order.builder()
                .id(activeOrderId)
                .orderNo(orderNo)
                .orderStatus("COMPLETED")
                .isactive("Y")
                .build();

        Invoice activeInvoice = Invoice.builder()
                .id(activeInvoiceId)
                .invoiceNo(invoiceNo)
                .status("UNPAID")
                .isactive("Y")
                .orderId(activeOrderId)
                .totalAmount(BigDecimal.TEN)
                .amountDue(BigDecimal.TEN)
                .build();
        activeInvoice.setClientId(clientId);
        activeInvoice.setOrgId(orgId);

        when(orderService.getOrder(eq(voidedOrderId))).thenReturn(activeOrder);
        when(invoiceRepository.findActiveByOrderIdAndClientIdAndOrgId(eq(activeOrderId), eq(clientId), eq(orgId)))
                .thenReturn(Optional.of(activeInvoice));

        Invoice result = invoiceService.getInvoiceByOrder(voidedOrderId);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(activeInvoiceId);
        assertThat(result.getOrderId()).isEqualTo(activeOrderId);
        assertThat(result.getStatus()).isEqualTo("UNPAID");
    }

    @Test
    void dailyBillCounterResetsAtBranchMidnightNotUtcMidnight() {
        // 19:00 UTC on 10 Oct is 00:30 on 11 Oct in India, so the bill belongs to the 11 Oct branch day.
        when(timezoneResolver.resolveTimezone(eq(clientId), eq(orgId))).thenReturn(java.time.ZoneId.of("Asia/Kolkata"));
        when(invoiceRepository.findMaxDailyBillNo(eq(clientId), eq(orgId), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(4);
        when(invoiceRepository.save(org.mockito.ArgumentMatchers.any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        Invoice invoice = Invoice.builder()
                .invoiceDate(java.time.LocalDateTime.of(2026, 10, 10, 19, 0))
                .totalAmount(BigDecimal.TEN)
                .amountDue(BigDecimal.TEN)
                .build();
        invoice.setClientId(clientId);
        invoice.setOrgId(orgId);

        invoiceService.createInvoice(invoice);

        org.mockito.ArgumentCaptor<java.time.LocalDateTime> start = org.mockito.ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        org.mockito.ArgumentCaptor<java.time.LocalDateTime> end = org.mockito.ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        org.mockito.Mockito.verify(invoiceRepository).findMaxDailyBillNo(eq(clientId), eq(orgId), start.capture(), end.capture());
        // Branch day 11 Oct 00:00 IST = 10 Oct 18:30 UTC, ending just before 11 Oct 18:30 UTC.
        assertThat(start.getValue()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 10, 18, 30));
        assertThat(end.getValue()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 11, 18, 30).minusNanos(1));
        assertThat(invoice.getDailyBillNo()).isEqualTo(5);
    }
}
