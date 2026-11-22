package com.restaurant.pos.invoice.service;

import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.invoice.domain.InvoiceLine;
import com.restaurant.pos.invoice.repository.InvoiceRepository;
import com.restaurant.pos.order.service.OrderService;
import com.restaurant.pos.order.domain.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.restaurant.pos.common.util.SecurityUtils;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final OrderService orderService;
    private final com.restaurant.pos.common.context.TimezoneResolver timezoneResolver;

    @Transactional(readOnly = true)
    public Invoice getInvoice(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenant();
        UUID orgId = requireSelectedOrgForRead();
        Invoice invoice = invoiceRepository.findByIdAndClientIdAndOrgId(id, tenantId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found or access denied"));

        if ("VOID".equalsIgnoreCase(invoice.getStatus()) || "N".equalsIgnoreCase(invoice.getIsactive())) {
            String baseInvoiceNo = invoice.getInvoiceNo();
            if (baseInvoiceNo != null && baseInvoiceNo.contains("_VOID_")) {
                baseInvoiceNo = baseInvoiceNo.substring(0, baseInvoiceNo.indexOf("_VOID_"));
            }
            Optional<Invoice> activeInvoice = invoiceRepository.findActiveByInvoiceNoAndClientIdAndOrgId(baseInvoiceNo, tenantId, orgId);
            if (activeInvoice.isPresent()) {
                invoice = activeInvoice.get();
            }
        }

        if (invoice.getLines() != null) {
            invoice.getLines().size();
        }
        return invoice;
    }

    @Transactional(readOnly = true)
    public Invoice getInvoiceByOrder(UUID orderId) {
        UUID tenantId = TenantContext.getCurrentTenant();
        UUID orgId = requireSelectedOrgForRead();

        UUID resolvedOrderId = orderId;
        try {
            Order order = orderService.getOrder(orderId);
            if (order != null) {
                resolvedOrderId = order.getId();
            }
        } catch (Exception ignored) {}
        final UUID activeOrderId = resolvedOrderId;

        Invoice invoice = invoiceRepository.findActiveByOrderIdAndClientIdAndOrgId(activeOrderId, tenantId, orgId)
                .orElseGet(() -> invoiceRepository.findByOrderIdAndClientIdAndOrgId(activeOrderId, tenantId, orgId)
                        .orElseThrow(() -> new ResourceNotFoundException("Invoice not found for order or access denied")));
        if (invoice.getLines() != null) {
            invoice.getLines().size();
        }
        return invoice;
    }

    @Transactional
    public Invoice createInvoice(Invoice invoice) {
        if (invoice.getClientId() == null) {
            invoice.setClientId(TenantContext.getCurrentTenant());
        }
        UUID selectedOrgId = TenantContext.getCurrentOrg();
        if (selectedOrgId == null && SecurityUtils.isSuperAdmin()) {
            throw new com.restaurant.pos.common.exception.BusinessException("Select a branch before creating a bill.");
        }
        if (selectedOrgId != null) {
            invoice.setOrgId(selectedOrgId);
        }

        // Generate daily bill number resetting everyday (customer sales only; expenses and vendor bills do not consume numbers)
        boolean isCustomerInvoice = invoice.getInvoiceType() == null
                || invoice.getInvoiceType() == com.restaurant.pos.invoice.domain.InvoiceType.CUSTOMER_INVOICE;
        if (isCustomerInvoice && (invoice.getDailyBillNo() == null || invoice.getDailyBillNo() <= 0)) {
            LocalDateTime date = invoice.getInvoiceDate();
            if (date == null) {
                date = LocalDateTime.now(java.time.ZoneOffset.UTC);
                invoice.setInvoiceDate(date);
            }
            // invoiceDate is stored in UTC; the bill counter resets at the branch's local midnight.
            java.time.ZoneId zone = timezoneResolver.resolveTimezone(invoice.getClientId(), invoice.getOrgId());
            java.time.LocalDate branchDay = date.atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDate();
            LocalDateTime start = branchDay.atStartOfDay(zone).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
            LocalDateTime end = branchDay.plusDays(1).atStartOfDay(zone).withZoneSameInstant(java.time.ZoneOffset.UTC)
                    .toLocalDateTime().minusNanos(1);
            int maxNo = invoiceRepository.findMaxDailyBillNo(invoice.getClientId(), invoice.getOrgId(), start, end);
            invoice.setDailyBillNo(maxNo + 1);
        }

        // Prevent duplicate creation from offline sync
        if (invoice.getInvoiceNo() != null) {
             Optional<Invoice> existing = invoiceRepository.findByInvoiceNoAndClientIdAndOrgId(
                    invoice.getInvoiceNo(), invoice.getClientId(), invoice.getOrgId()
             );
             
             if (existing.isPresent()) {
                 return existing.get();
             }
        }

        // Safeguard: Sync bidirectional parent-child relationships for lines
        if (invoice.getLines() != null) {
            for (InvoiceLine line : invoice.getLines()) {
                line.setInvoice(invoice);
            }
        }

        return invoiceRepository.save(invoice);
    }

    private UUID requireSelectedOrgForRead() {
        UUID selectedOrgId = TenantContext.getCurrentOrg();
        if (selectedOrgId == null && SecurityUtils.isSuperAdmin()) {
            throw new com.restaurant.pos.common.exception.BusinessException("Select a branch before viewing bills.");
        }
        return selectedOrgId;
    }
}
