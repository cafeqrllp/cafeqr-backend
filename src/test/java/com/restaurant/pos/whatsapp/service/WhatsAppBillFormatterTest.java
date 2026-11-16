package com.restaurant.pos.whatsapp.service;

import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.common.context.TimezoneResolver;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.purchasing.domain.Currency;
import com.restaurant.pos.purchasing.repository.CurrencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WhatsAppBillFormatterTest {

    @Mock
    private CurrencyRepository currencyRepository;

    @Mock
    private TimezoneResolver timezoneResolver;

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
        organization = Organization.builder().id(orgId).clientId(clientId).name("Riyas Test Restaurant 1").build();
        client = Client.builder().id(clientId).name("Test Client").build();
    }

    private Order order(String paymentStatus) {
        Order order = Order.builder()
                .id(UUID.randomUUID())
                .orderNo("SO-2026-0000243-HQ")
                .customerName("Riyas")
                .grandTotal(new BigDecimal("84.00"))
                .paymentStatus(paymentStatus)
                .build();
        order.setClientId(clientId);
        order.setOrgId(orgId);
        order.setCreatedAt(LocalDateTime.of(2026, 10, 9, 22, 0));
        return order;
    }

    private void inr() {
        when(currencyRepository.findByClientIdAndOrgIdAndIsDefaultTrue(clientId, orgId))
                .thenReturn(List.of(Currency.builder().code("INR").symbol("₹").decimalPlaces(2).build()));
    }

    @Test
    void digitalBill_paidOrder_fullLayout() {
        inr();
        Invoice invoice = Invoice.builder()
                .invoiceNo("INV-2026-0000233-HQ")
                .dailyBillNo(23)
                .invoiceDate(LocalDateTime.of(2026, 11, 9, 22, 0))
                .build();

        String msg = formatter.formatDigitalBill(order("PAID"), invoice, organization, client);

        assertEquals(
                "🧾 *Your bill from Riyas Test Restaurant 1*\n\n"
                        + "Hi Riyas, thanks for visiting!\n\n"
                        + "Bill No: INV-2026-0000233-HQ\n"
                        + "Token: #23\n"
                        + "Date: 09 Nov 2026, 10:00 PM\n"
                        + "Total: *₹ 84.00* (Paid)\n"
                        + "\n_Powered by Cafe QR POS_",
                msg);
    }

    @Test
    void digitalBill_creditOrder_showsDue() {
        inr();
        Order order = order("PENDING");
        order.setReference("CREDIT");
        String msg = formatter.formatDigitalBill(order, null, organization, client);
        assertTrue(msg.contains("Total: *₹ 84.00* (Due – on credit)"), msg);
        assertTrue(msg.contains("Bill No: SO-2026-0000243-HQ"), msg);
        assertFalse(msg.contains("http"), msg);
    }

    @Test
    void digitalBill_partialPayment() {
        inr();
        String msg = formatter.formatDigitalBill(order("PARTIAL"), null, organization, client);
        assertTrue(msg.contains("(Partly paid)"), msg);
    }

    @Test
    void digitalBill_missingNumbersAndGuestName_noNullText() {
        Order order = order("PAID");
        order.setOrderNo(null);
        order.setCustomerName("Walk-in Guest");
        String msg = formatter.formatDigitalBill(order, null, organization, client);
        assertFalse(msg.contains("null"), msg);
        assertFalse(msg.contains("Bill No"), msg);
        assertTrue(msg.contains("Thanks for visiting!"), msg);
        assertTrue(msg.contains("Total: *84.00* (Paid)"), msg); // no currency symbol configured → none invented
    }

    @Test
    void digitalBill_stripsWhatsAppMarkupFromNames() {
        organization.setName("*Cafe_Bold*");
        Order order = order("PAID");
        order.setCustomerName("_Ann_ *B*");
        String msg = formatter.formatDigitalBill(order, null, organization, client);
        assertTrue(msg.startsWith("🧾 *Your bill from CafeBold*\n"), msg);
        assertTrue(msg.contains("Hi Ann B, thanks"), msg);
    }

    @Test
    void digitalBill_usesOrderCurrencyDecimals() {
        UUID currencyId = UUID.randomUUID();
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(
                Currency.builder().id(currencyId).code("OMR").symbol("OMR").decimalPlaces(3).build()));
        Order order = order("PAID");
        order.setCurrencyId(currencyId);
        order.setGrandTotal(new BigDecimal("4.5"));
        String msg = formatter.formatDigitalBill(order, null, organization, client);
        assertTrue(msg.contains("Total: *OMR 4.500* (Paid)"), msg);
    }

    // ---- date shown in store timezone (same as invoice PDF) ----

    @Test
    void date_convertedFromUtcToStoreTimezone() {
        when(timezoneResolver.resolveTimezone(clientId, orgId)).thenReturn(java.time.ZoneId.of("Asia/Kolkata"));
        Invoice invoice = Invoice.builder().invoiceNo("INV-1").invoiceDate(LocalDateTime.of(2026, 11, 9, 22, 0)).build();
        String msg = formatter.formatDigitalBill(order("PAID"), invoice, organization, client);
        assertTrue(msg.contains("Date: 10 Nov 2026, 03:30 AM"), msg); // 22:00 UTC = 03:30 IST next day
    }

    @Test
    void date_dubaiStore() {
        when(timezoneResolver.resolveTimezone(clientId, orgId)).thenReturn(java.time.ZoneId.of("Asia/Dubai"));
        String msg = formatter.formatDigitalBill(order("PAID"), null, organization, client); // createdAt 09 Oct 22:00 UTC
        assertTrue(msg.contains("Date: 10 Oct 2026, 02:00 AM"), msg);
    }

    @Test
    void date_noTimezoneConfigured_usesStoreCountry() {
        when(timezoneResolver.resolveTimezone(clientId, orgId)).thenReturn(java.time.ZoneId.of("UTC"));
        client.setCountry("ZA");
        String msg = formatter.formatDigitalBill(order("PAID"), null, organization, client);
        assertTrue(msg.contains("Date: 10 Oct 2026, 12:00 AM"), msg); // UTC+2
    }

    // ---- phone normalisation ----

    @Test
    void phone_india() {
        assertEquals("919847920009", formatter.normalizePhoneNumber("98479 20009", "IN"));
        assertEquals("919847920009", formatter.normalizePhoneNumber("09847920009", "IN"));
        assertEquals("919847920009", formatter.normalizePhoneNumber("919847920009", "IN"));
        assertEquals("919198765432", formatter.normalizePhoneNumber("9198765432", "IN")); // local number starting 91
    }

    @Test
    void phone_southAfrica_localNotSentToIndia() {
        assertEquals("27821234567", formatter.normalizePhoneNumber("082 123 4567", "ZA"));
        assertEquals("27821234567", formatter.normalizePhoneNumber("27821234567", "ZA"));
    }

    @Test
    void phone_uaeAndKsa() {
        assertEquals("971501234567", formatter.normalizePhoneNumber("050 123 4567", "AE"));
        assertEquals("971501234567", formatter.normalizePhoneNumber("501234567", "AE"));
        assertEquals("966501234567", formatter.normalizePhoneNumber("0501234567", "SA"));
    }

    @Test
    void phone_explicitInternationalWins() {
        assertEquals("971501234567", formatter.normalizePhoneNumber("+971 50 123 4567", "IN"));
        assertEquals("27821234567", formatter.normalizePhoneNumber("0027821234567", "IN"));
    }

    @Test
    void phone_invalid() {
        assertNull(formatter.normalizePhoneNumber("12345", "AE"));
        assertNull(formatter.normalizePhoneNumber("", "IN"));
        assertNull(formatter.normalizePhoneNumber(null, "IN"));
    }

    @Test
    void phone_unknownCountry_keepsLegacyBehaviour() {
        assertEquals("919847920009", formatter.normalizePhoneNumber("9847920009"));
        assertEquals("919847920009", formatter.normalizePhoneNumber("9847920009", null));
    }

}
