package com.restaurant.pos.whatsapp.service;

import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.purchasing.domain.Currency;
import com.restaurant.pos.purchasing.repository.CurrencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppBillFormatterTest {

    @Mock
    private CurrencyRepository currencyRepository;

    @InjectMocks
    private WhatsAppBillFormatter formatter;

    private UUID clientId;
    private UUID orgId;
    private Organization organization;
    private Client client;

    @BeforeEach
    void setUp() {
        clientId = UUID.randomUUID();
        orgId = UUID.randomUUID();

        organization = Organization.builder()
                .id(orgId)
                .clientId(clientId)
                .name("Riyas Test Restaurant 1")
                .address("Thrissur, Kerala")
                .phone("7012120844")
                .gstin("32AAAAA0000A1Z5")
                .googleMapsUrl("https://maps.google.com/test")
                .build();

        client = Client.builder()
                .id(clientId)
                .name("Test Client")
                .fssaiNumber("12345678901234")
                .build();
    }

    @Test
    void testFormatBillMessage_WithDollarCurrencyFromMaster() {
        UUID currencyId = UUID.randomUUID();
        Currency dollarCurrency = Currency.builder()
                .id(currencyId)
                .code("USD")
                .symbol("$")
                .build();

        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(dollarCurrency));

        OrderLine line1 = OrderLine.builder()
                .productName("Apple")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("84.00"))
                .lineTotal(new BigDecimal("84.00"))
                .taxRate(new BigDecimal("5.00"))
                .taxAmount(new BigDecimal("4.00"))
                .build();

        Order order = Order.builder()
                .id(UUID.randomUUID())
                .orderNo("SO-2026-0000243-HQ")
                .currencyId(currencyId)
                .customerName("Riyas")
                .customerPhone("7012120844")
                .fulfillmentType("TAKEAWAY")
                .lines(List.of(line1))
                .grossAmount(new BigDecimal("84.00"))
                .totalAmount(new BigDecimal("84.00"))
                .totalTaxAmount(new BigDecimal("4.00"))
                .grandTotal(new BigDecimal("84.00"))
                .paymentStatus("PAID")
                .reference("CASH")
                .build();
        order.setClientId(clientId);
        order.setOrgId(orgId);
        order.setCreatedAt(LocalDateTime.of(2026, 10, 9, 22, 0));

        Invoice invoice = Invoice.builder()
                .invoiceNo("INV-2026-0000233-HQ")
                .dailyBillNo(23)
                .invoiceDate(LocalDateTime.of(2026, 10, 9, 22, 0))
                .build();

        String message = formatter.formatBillMessage(order, invoice, organization, client);
        assertNotNull(message);

        // Verify Header
        assertTrue(message.contains("🧾 *TAX INVOICE*"));
        assertTrue(message.contains("🍽 *RIYAS TEST RESTAURANT 1*"));
        assertTrue(message.contains("Hi *Riyas*, thank you for dining with us! 😊"));

        // Verify Token & Invoice
        assertTrue(message.contains("*Token / Bill:* #23  |  *Invoice No:* #INV-2026-0000233-HQ"));
        assertTrue(message.contains("*Type:* 🥡 Takeaway"));

        // Verify Item Line
        assertTrue(message.contains("• 1 × *Apple*  ➜  $ 84.00"));

        // Verify Option B Totals (Gross Subtotal + Includes Taxes/GST)
        assertTrue(message.contains("*Subtotal:* $ 84.00"));
        assertTrue(message.contains("*Includes Taxes / GST:* $ 4.00"));
        assertTrue(message.contains("CGST (2.5%): $ 2.00"));
        assertTrue(message.contains("SGST (2.5%): $ 2.00"));
        assertTrue(message.contains("*TOTAL PAID:* *$ 84.00*"));
        assertTrue(message.contains("*Payment Mode:* CASH (PAID)"));

        // Verify Compliance
        assertTrue(message.contains("📍 *Thrissur, Kerala*"));
        assertTrue(message.contains("📞 Contact: 7012120844"));
        assertTrue(message.contains("GSTIN:* 32AAAAA0000A1Z5"));
        assertTrue(message.contains("FSSAI:* 12345678901234"));
        assertTrue(message.contains("https://maps.google.com/test"));
    }

    @Test
    void testFormatBillMessage_NoCurrencyInMasterProfile_ShouldNotProvideDefaultSymbol() {
        when(currencyRepository.findByClientIdAndOrgIdAndIsDefaultTrue(clientId, orgId)).thenReturn(Collections.emptyList());
        when(currencyRepository.findByClientIdAndOrgIdOrderByCodeAsc(clientId, orgId)).thenReturn(Collections.emptyList());
        when(currencyRepository.findByClientIdAndIsDefaultTrue(clientId)).thenReturn(Collections.emptyList());
        when(currencyRepository.findByClientIdOrderByCodeAsc(clientId)).thenReturn(Collections.emptyList());

        OrderLine line1 = OrderLine.builder()
                .productName("Burger")
                .quantity(BigDecimal.valueOf(2))
                .unitPrice(new BigDecimal("150.00"))
                .lineTotal(new BigDecimal("300.00"))
                .build();

        Order order = Order.builder()
                .id(UUID.randomUUID())
                .orderNo("SO-001")
                .currencyId(null)
                .customerName("Walk-in Guest")
                .lines(List.of(line1))
                .grossAmount(new BigDecimal("300.00"))
                .totalAmount(new BigDecimal("300.00"))
                .grandTotal(new BigDecimal("300.00"))
                .paymentStatus("PAID")
                .reference("UPI")
                .build();
        order.setClientId(clientId);
        order.setOrgId(orgId);
        order.setCreatedAt(LocalDateTime.now());

        String message = formatter.formatBillMessage(order, null, organization, client);

        // Verify that NO currency symbol (like ₹ or $) is provided when master profile has none
        assertFalse(message.contains("$"));
        assertFalse(message.contains("₹"));
        assertTrue(message.contains("• 2 × *Burger*  ➜  300.00"));
        assertTrue(message.contains("_(Rate: 150.00 each)_"));
        assertTrue(message.contains("*TOTAL PAID:* *300.00*"));
    }

    @Test
    void testFormatBillMessage_DineInWithTable() {
        Currency inrCurrency = Currency.builder()
                .code("INR")
                .symbol("₹")
                .build();

        when(currencyRepository.findByClientIdAndOrgIdAndIsDefaultTrue(clientId, orgId))
                .thenReturn(List.of(inrCurrency));

        OrderLine line = OrderLine.builder()
                .productName("Biryani")
                .quantity(BigDecimal.ONE)
                .lineTotal(new BigDecimal("250.00"))
                .description("Extra Raita")
                .build();

        Order order = Order.builder()
                .id(UUID.randomUUID())
                .orderNo("SO-002")
                .tableNumber("T-4")
                .fulfillmentType("DINE_IN")
                .lines(List.of(line))
                .grossAmount(new BigDecimal("250.00"))
                .totalAmount(new BigDecimal("250.00"))
                .grandTotal(new BigDecimal("250.00"))
                .paymentStatus("PAID")
                .build();
        order.setClientId(clientId);
        order.setOrgId(orgId);
        order.setCreatedAt(LocalDateTime.now());

        String message = formatter.formatBillMessage(order, null, organization, client);
        assertTrue(message.contains("*Type:* 🍽️ Dine-In (Table T-4)"));
        assertTrue(message.contains("• 1 × *Biryani*  ➜  ₹ 250.00"));
        assertTrue(message.contains("_(Note: Extra Raita)_"));
    }
}
