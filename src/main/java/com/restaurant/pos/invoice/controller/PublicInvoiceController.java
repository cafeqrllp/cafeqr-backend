package com.restaurant.pos.invoice.controller;

import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.entity.SystemConfiguration;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.repository.SystemConfigurationRepository;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.dto.PublicDigitalInvoiceDto;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.domain.PaymentSplit;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.order.repository.PaymentSplitRepository;
import com.restaurant.pos.whatsapp.service.WhatsAppBillFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/public/invoices")
@RequiredArgsConstructor
public class PublicInvoiceController {

    private final InvoiceRepository invoiceRepository;
    private final OrderRepository orderRepository;
    private final OrganizationRepository organizationRepository;
    private final ClientRepository clientRepository;
    private final SystemConfigurationRepository systemConfigurationRepository;
    private final com.restaurant.pos.purchasing.repository.CustomerRepository customerRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentSplitRepository paymentSplitRepository;
    private final WhatsAppBillFormatter whatsAppBillFormatter;

    /**
     * Public, unauthenticated endpoint to view a digital invoice e-receipt.
     * Polymorphically accepts either invoice ID or order ID.
     */
    @GetMapping("/view/{id}")
    public ResponseEntity<ApiResponse<PublicDigitalInvoiceDto>> getPublicInvoiceView(@PathVariable UUID id) {
        if (id == null) {
            throw new ResourceNotFoundException("Invoice ID cannot be null");
        }

        Invoice invoice = null;
        Order order = null;

        // 1. Try finding by Invoice ID
        Optional<Invoice> invoiceOpt = invoiceRepository.findById(id);
        if (invoiceOpt.isPresent()) {
            invoice = invoiceOpt.get();
            if (invoice.getOrderId() != null) {
                order = orderRepository.findByIdWithLines(invoice.getOrderId()).orElse(null);
            }
        }

        // 2. If not found by invoice id, try finding as Order ID
        if (invoice == null && order == null) {
            Optional<Order> orderOpt = orderRepository.findByIdWithLines(id);
            if (orderOpt.isPresent()) {
                order = orderOpt.get();
                List<Invoice> invoices = invoiceRepository.findByOrderId(order.getId());
                if (!invoices.isEmpty()) {
                    invoice = invoices.get(0);
                }
            }
        }

        if (invoice == null && order == null) {
            throw new ResourceNotFoundException("Invoice or order not found for identifier: " + id);
        }

        UUID orgId = order != null && order.getOrgId() != null 
                ? order.getOrgId() 
                : (invoice != null ? invoice.getOrgId() : null);
        UUID clientId = order != null && order.getClientId() != null 
                ? order.getClientId() 
                : (invoice != null ? invoice.getClientId() : null);

        Organization org = orgId != null ? organizationRepository.findById(orgId).orElse(null) : null;
        Client client = clientId != null ? clientRepository.findById(clientId).orElse(null) : null;

        SystemConfiguration sysConfig = null;
        if (clientId != null) {
            if (orgId != null) {
                sysConfig = systemConfigurationRepository.findFirstByClientIdAndOrgId(clientId, orgId).orElse(null);
            }
            if (sysConfig == null) {
                sysConfig = systemConfigurationRepository.findFirstByClientIdAndOrgIdIsNull(clientId).orElse(null);
            }
        }

        String currencySymbol = whatsAppBillFormatter.resolveCurrencySymbol(order, org, client);
        Integer currencyDecimals = whatsAppBillFormatter.resolveCurrencyDecimals(order, org, client);
        int dp = currencyDecimals != null ? currencyDecimals : 2;
        String taxLabel = (sysConfig != null && sysConfig.getTaxLabelGlobal() != null && !sysConfig.getTaxLabelGlobal().isBlank())
                ? sysConfig.getTaxLabelGlobal().trim()
                : "GST";

        // Store Identity & Metadata
        String storeName = org != null && org.getName() != null && !org.getName().isBlank()
                ? org.getName().trim()
                : (client != null && client.getName() != null && !client.getName().isBlank() ? client.getName().trim() : "Restaurant");

        String storeAddress = org != null && org.getAddress() != null && !org.getAddress().isBlank()
                ? org.getAddress().trim()
                : (client != null ? client.getAddress() : null);

        String storePhone = org != null && org.getPhone() != null && !org.getPhone().isBlank()
                ? org.getPhone().trim()
                : (client != null ? client.getPhone() : null);

        String gstin = org != null && org.getGstin() != null && !org.getGstin().isBlank()
                ? org.getGstin().trim()
                : (client != null ? client.getGstNumber() : null);

        String fssai = client != null ? client.getFssaiNumber() : null;

        String logoUrl = org != null && org.getLogoUrl() != null && !org.getLogoUrl().isBlank()
                ? org.getLogoUrl().trim()
                : (client != null ? client.getLogoUrl() : null);

        String googleMapsUrl = org != null && org.getGoogleMapsUrl() != null && !org.getGoogleMapsUrl().isBlank()
                ? org.getGoogleMapsUrl().trim()
                : (client != null ? client.getGoogleMapsUrl() : null);

        String billFooter = sysConfig != null ? sysConfig.getBillFooter() : null;

        // Line Items
        List<PublicDigitalInvoiceDto.LineItemDto> lineDtos = new ArrayList<>();
        BigDecimal detectedTaxRate = null;
        if (order != null && order.getLines() != null) {
            for (OrderLine l : order.getLines()) {
                if (l == null) continue;
                if (detectedTaxRate == null && l.getTaxRate() != null && l.getTaxRate().compareTo(BigDecimal.ZERO) > 0) {
                    detectedTaxRate = l.getTaxRate();
                }
                lineDtos.add(PublicDigitalInvoiceDto.LineItemDto.builder()
                        .productId(l.getProductId())
                        .productName(l.getProductName() != null ? l.getProductName().trim() : "Item")
                        .quantity(l.getQuantity() != null ? l.getQuantity() : BigDecimal.ONE)
                        .unitPrice(l.getUnitPrice() != null ? l.getUnitPrice() : BigDecimal.ZERO)
                        .taxRate(l.getTaxRate())
                        .lineTotal(l.getLineTotal() != null ? l.getLineTotal() : BigDecimal.ZERO)
                        .description(l.getDescription())
                        .build());
            }
        }

        // Financials
        BigDecimal grossAmount = order != null ? order.getGrossAmount() : (invoice != null ? invoice.getGrossAmount() : null);
        BigDecimal discount = order != null ? order.getTotalDiscountAmount() : (invoice != null ? invoice.getTotalDiscountAmount() : null);
        BigDecimal subtotal = order != null && order.getTotalAmount() != null
                ? order.getTotalAmount()
                : (invoice != null && invoice.getTotalAmount() != null ? invoice.getTotalAmount() : (grossAmount != null ? grossAmount : BigDecimal.ZERO));
        BigDecimal totalTax = order != null ? order.getTotalTaxAmount() : (invoice != null ? invoice.getTotalTaxAmount() : null);
        BigDecimal roundOff = order != null ? order.getRoundOffAmount() : (invoice != null ? invoice.getRoundOffAmount() : null);
        BigDecimal grandTotal = order != null && order.getGrandTotal() != null
                ? order.getGrandTotal()
                : (invoice != null && invoice.getTotalAmount() != null ? invoice.getTotalAmount() : BigDecimal.ZERO);

        BigDecimal cgstRate = null;
        BigDecimal cgstAmount = null;
        BigDecimal sgstRate = null;
        BigDecimal sgstAmount = null;

        if (totalTax != null && totalTax.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal halfTax = totalTax.divide(BigDecimal.valueOf(2), dp, RoundingMode.HALF_UP);
            BigDecimal otherHalf = totalTax.subtract(halfTax);
            cgstAmount = halfTax;
            sgstAmount = otherHalf;
            if (detectedTaxRate != null && detectedTaxRate.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal halfRate = detectedTaxRate.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                cgstRate = halfRate;
                sgstRate = halfRate;
            }
        }

        String invNo = invoice != null && invoice.getInvoiceNo() != null
                ? invoice.getInvoiceNo()
                : (order != null ? order.getOrderNo() : "N/A");

        Integer dailyBillNo = invoice != null && invoice.getDailyBillNo() != null
                ? invoice.getDailyBillNo()
                : (order != null ? order.getDailyBillNo() : null);

        LocalDateTime invoiceDate = invoice != null && invoice.getInvoiceDate() != null
                ? invoice.getInvoiceDate()
                : (order != null && order.getCreatedAt() != null ? order.getCreatedAt() : LocalDateTime.now());

        String fulfillment = order != null ? order.getFulfillmentType() : null;
        String tableNum = order != null ? order.getTableNumber() : null;

        // Payment splits resolution (for MIXED payments parity with invoicePdf.js)
        List<PublicDigitalInvoiceDto.PaymentSplitDto> splitDtos = new ArrayList<>();
        UUID orderIdForPayments = order != null ? order.getId() : (invoice != null ? invoice.getOrderId() : null);
        if (orderIdForPayments != null) {
            try {
                List<Payment> payments = paymentRepository.findByOrderId(orderIdForPayments);
                for (Payment p : payments) {
                    if ("Y".equalsIgnoreCase(p.getIsactive()) && !"VOID".equalsIgnoreCase(p.getDocStatus())) {
                        List<PaymentSplit> splits = paymentSplitRepository.findByPaymentIdOrderByCreatedAtAsc(p.getId());
                        if (splits != null && !splits.isEmpty()) {
                            for (PaymentSplit s : splits) {
                                splitDtos.add(PublicDigitalInvoiceDto.PaymentSplitDto.builder()
                                        .paymentMethod(s.getPaymentMethod() != null ? s.getPaymentMethod().toUpperCase() : "PAYMENT")
                                        .amount(s.getAmount() != null ? s.getAmount() : BigDecimal.ZERO)
                                        .referenceNo(s.getReferenceNo())
                                        .build());
                            }
                        } else if ("MIXED".equalsIgnoreCase(p.getPaymentMethod()) && p.getAmountPaid() != null) {
                            BigDecimal total = p.getAmountPaid();
                            BigDecimal half = total.divide(BigDecimal.valueOf(2), dp, RoundingMode.HALF_UP);
                            BigDecimal rem = total.subtract(half);
                            splitDtos.add(PublicDigitalInvoiceDto.PaymentSplitDto.builder()
                                    .paymentMethod("CASH")
                                    .amount(half)
                                    .build());
                            splitDtos.add(PublicDigitalInvoiceDto.PaymentSplitDto.builder()
                                    .paymentMethod("ONLINE")
                                    .amount(rem)
                                    .build());
                        } else if (payments.size() > 1 && p.getAmountPaid() != null && p.getAmountPaid().compareTo(BigDecimal.ZERO) > 0) {
                            splitDtos.add(PublicDigitalInvoiceDto.PaymentSplitDto.builder()
                                    .paymentMethod(p.getPaymentMethod() != null ? p.getPaymentMethod().toUpperCase() : "PAYMENT")
                                    .amount(p.getAmountPaid())
                                    .referenceNo(p.getReferenceNo())
                                    .build());
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Error fetching payment splits for public invoice: {}", ex.getMessage());
            }
        }

        String payMethod = order != null && order.getReference() != null && !order.getReference().isBlank()
                ? order.getReference().toUpperCase()
                : (order != null && order.getPaymentMethod() != null ? order.getPaymentMethod().toUpperCase() : "CASH");

        if (!splitDtos.isEmpty()) {
            payMethod = "MIXED";
        }

        String payStatus = order != null && order.getPaymentStatus() != null ? order.getPaymentStatus() : "PAID";

        String customerAddress = null;
        String customerGstin = null;
        if (order != null && order.getCustomerId() != null) {
            try {
                com.restaurant.pos.purchasing.domain.Customer c = customerRepository.findById(order.getCustomerId()).orElse(null);
                if (c != null) {
                    customerAddress = c.getAddress();
                    customerGstin = c.getGstNumber();
                }
            } catch (Exception ignored) {}
        }

        PublicDigitalInvoiceDto dto = PublicDigitalInvoiceDto.builder()
                .invoiceId(invoice != null ? invoice.getId() : (order != null ? order.getId() : null))
                .orderId(order != null ? order.getId() : null)
                .invoiceNo(invNo)
                .orderNo(order != null ? order.getOrderNo() : null)
                .dailyBillNo(dailyBillNo)
                .invoiceDate(invoiceDate)
                .status(invoice != null ? invoice.getStatus() : (order != null && order.getOrderStatus() != null ? order.getOrderStatus() : "PAID"))
                .fulfillmentType(fulfillment)
                .tableNumber(tableNum)
                .customerName(order != null ? order.getCustomerName() : null)
                .customerPhone(order != null ? order.getCustomerPhone() : null)
                .customerAddress(customerAddress)
                .customerGstin(customerGstin)
                .paymentMethod(payMethod)
                .paymentStatus(payStatus)
                .currencySymbol(currencySymbol)
                .currencyDecimals(currencyDecimals)
                .taxLabel(taxLabel)
                .grossAmount(grossAmount)
                .totalDiscountAmount(discount)
                .subtotal(subtotal)
                .totalTaxAmount(totalTax)
                .cgstRate(cgstRate)
                .cgstAmount(cgstAmount)
                .sgstRate(sgstRate)
                .sgstAmount(sgstAmount)
                .roundOffAmount(roundOff)
                .grandTotal(grandTotal)
                .lines(lineDtos)
                .paymentSplits(splitDtos.isEmpty() ? null : splitDtos)
                .storeName(storeName)
                .logoUrl(logoUrl)
                .storeAddress(storeAddress)
                .storePhone(storePhone)
                .gstin(gstin)
                .fssaiNumber(fssai)
                .googleMapsUrl(googleMapsUrl)
                .billFooter(billFooter)
                .build();

        return ResponseEntity.ok(ApiResponse.success(dto));
    }
}
