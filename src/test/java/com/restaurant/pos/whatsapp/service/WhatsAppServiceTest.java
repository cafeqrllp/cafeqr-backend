package com.restaurant.pos.whatsapp.service;

import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WhatsAppServiceTest {

    private static final String PDF_B64 = Base64.getEncoder().encodeToString("%PDF-1.7 test invoice".getBytes());

    private OrderRepository orderRepository;
    private InvoiceRepository invoiceRepository;
    private ClientRepository clientRepository;
    private WhatsAppService service;
    private UUID orderId;
    private UUID clientId;
    private Order order;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        invoiceRepository = mock(InvoiceRepository.class);
        clientRepository = mock(ClientRepository.class);
        WhatsAppBillFormatter formatter = new WhatsAppBillFormatter(); // no currency repo → no symbol, 2 dp
        service = spy(new WhatsAppService(formatter, mock(OrganizationRepository.class), clientRepository,
                invoiceRepository, mock(CustomerRepository.class), orderRepository));
        ReflectionTestUtils.setField(service, "defaultGatewayUrl", "http://gateway.invalid");
        doReturn(true).when(service).dispatchToGateway(any(), any(), any(), any(), any(), any());

        orderId = UUID.randomUUID();
        clientId = UUID.randomUUID();
        order = Order.builder().id(orderId).orderNo("SO-1").grandTotal(new BigDecimal("84.00"))
                .paymentStatus("PAID").lines(new ArrayList<>()).build();
        order.setClientId(clientId);
        when(orderRepository.findByIdWithLines(orderId)).thenReturn(Optional.of(order));
        when(invoiceRepository.findByOrderId(orderId)).thenReturn(List.of(
                Invoice.builder().id(UUID.randomUUID()).invoiceNo("INV-2026-0001").status("PAID").build()));
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(Client.builder().id(clientId).country("IN").build()));
    }

    @Test
    void posSend_attachesInvoicePdfWithCaption() {
        boolean sent = service.sendOrderBillWithAttachment(orderId, "98479 20009", PDF_B64, null);

        assertTrue(sent);
        verify(service).dispatchToGateway(eq("client_" + clientId), eq("client_" + clientId), eq("+919847920009"),
                argThat(text -> text.contains("Bill No: INV-2026-0001") && !text.contains("http")),
                eq(PDF_B64), eq("Invoice-INV-2026-0001.pdf"));
    }

    @Test
    void posSend_usesPosFilenameAndStripsDataUriPrefix() {
        service.sendOrderBillWithAttachment(orderId, "9847920009", "data:application/pdf;base64," + PDF_B64, "Invoice-SO-1.pdf");
        verify(service).dispatchToGateway(any(), any(), any(), any(), eq(PDF_B64), eq("Invoice-SO-1.pdf"));
    }

    @Test
    void nonPdfAttachment_isDropped_billStillSent() {
        String png = Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        assertTrue(service.sendOrderBillWithAttachment(orderId, "9847920009", png, null));
        verify(service).dispatchToGateway(any(), any(), any(), any(), isNull(), isNull());
    }

    @Test
    void reservedForPos_serverSendIsSkipped_thenPosSendGoesThrough() {
        service.reserveForClientDispatch(orderId);
        order.setCustomerPhone("9847920009");

        assertFalse(service.sendOrderSettledBill(order));
        verify(service, never()).dispatchToGateway(any(), any(), any(), any(), any(), any());

        assertTrue(service.sendOrderBillWithAttachment(orderId, "9847920009", PDF_B64, null));
        verify(service).dispatchToGateway(any(), any(), any(), any(), eq(PDF_B64), any());
    }

    @Test
    void serverSendWithoutPhone_doesNotBlockPosSendWithPhone() {
        assertFalse(service.sendOrderSettledBill(order)); // no phone anywhere on the order

        assertTrue(service.sendOrderBillWithAttachment(orderId, "9847920009", PDF_B64, null));
        verify(service, times(1)).dispatchToGateway(any(), any(), eq("+919847920009"), any(), eq(PDF_B64), any());
    }

    @Test
    void duplicateWithinWindow_isNotSentTwice() {
        service.sendOrderBillWithAttachment(orderId, "9847920009", PDF_B64, null);
        service.sendOrderBillWithAttachment(orderId, "9847920009", PDF_B64, null);
        verify(service, times(1)).dispatchToGateway(any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedDispatch_releasesClaimForRetry() {
        doReturn(false).doReturn(true).when(service).dispatchToGateway(any(), any(), any(), any(), any(), any());
        assertFalse(service.sendOrderBillWithAttachment(orderId, "9847920009", PDF_B64, null));
        assertTrue(service.sendOrderBillWithAttachment(orderId, "9847920009", PDF_B64, null));
        verify(service, times(2)).dispatchToGateway(any(), any(), any(), any(), any(), any());
    }

    @Test
    void storeCountryDrivesDialCode() {
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(Client.builder().id(clientId).country("ZA").build()));
        service.sendOrderBillWithAttachment(orderId, "082 123 4567", PDF_B64, null);
        verify(service).dispatchToGateway(any(), any(), eq("+27821234567"), any(), any(), any());
    }

}
