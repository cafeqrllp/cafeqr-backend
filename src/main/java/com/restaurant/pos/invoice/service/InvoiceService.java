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

    @Transactional(readOnly = true)
    public Invoice getInvoice(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenant();
        Invoice invoice;
        if (SecurityUtils.isSuperAdmin()) {
            invoice = invoiceRepository.findByIdAndClientId(id, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Invoice not found or access denied"));
        } else {
            invoice = invoiceRepository.findByIdAndClientIdAndOrgId(id, tenantId, TenantContext.getCurrentOrg())
                    .orElseThrow(() -> new ResourceNotFoundException("Invoice not found or access denied"));
        }

        if ("VOID".equalsIgnoreCase(invoice.getStatus()) || "N".equalsIgnoreCase(invoice.getIsactive())) {
            String baseInvoiceNo = invoice.getInvoiceNo();
            if (baseInvoiceNo != null && baseInvoiceNo.contains("_VOID_")) {
                baseInvoiceNo = baseInvoiceNo.substring(0, baseInvoiceNo.indexOf("_VOID_"));
            }
            UUID orgId = SecurityUtils.isSuperAdmin() ? null : TenantContext.getCurrentOrg();
            Optional<Invoice> activeInvoice = orgId == null
                    ? invoiceRepository.findActiveByInvoiceNoAndClientId(baseInvoiceNo, tenantId)
                    : invoiceRepository.findActiveByInvoiceNoAndClientIdAndOrgId(baseInvoiceNo, tenantId, orgId);
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
        UUID orgId = SecurityUtils.isSuperAdmin() ? null : TenantContext.getCurrentOrg();

        UUID resolvedOrderId = orderId;
        try {
            Order order = orderService.getOrder(orderId);
            if (order != null) {
                resolvedOrderId = order.getId();
            }
        } catch (Exception ignored) {}
        final UUID activeOrderId = resolvedOrderId;

        Invoice invoice;
        if (SecurityUtils.isSuperAdmin()) {
            invoice = invoiceRepository.findActiveByOrderIdAndClientId(activeOrderId, tenantId)
                    .orElseGet(() -> invoiceRepository.findByOrderIdAndClientId(activeOrderId, tenantId)
                            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found for order or access denied")));
        } else {
            invoice = invoiceRepository.findActiveByOrderIdAndClientIdAndOrgId(activeOrderId, tenantId, orgId)
                    .orElseGet(() -> invoiceRepository.findByOrderIdAndClientIdAndOrgId(activeOrderId, tenantId, orgId)
                            .orElseThrow(() -> new ResourceNotFoundException("Invoice not found for order or access denied")));
        }
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
        if (invoice.getOrgId() == null && !SecurityUtils.isSuperAdmin()) {
            invoice.setOrgId(TenantContext.getCurrentOrg());
        }

        // Generate daily bill number resetting everyday
        if (invoice.getDailyBillNo() == null || invoice.getDailyBillNo() <= 0) {
            LocalDateTime date = invoice.getInvoiceDate();
            if (date == null) {
                date = LocalDateTime.now();
                invoice.setInvoiceDate(date);
            }
            LocalDateTime start = date.toLocalDate().atStartOfDay();
            LocalDateTime end = date.toLocalDate().atTime(23, 59, 59, 999999999);
            int maxNo = invoiceRepository.findMaxDailyBillNo(invoice.getClientId(), invoice.getOrgId(), start, end);
            invoice.setDailyBillNo(maxNo + 1);
        }

        // Prevent duplicate creation from offline sync
        if (invoice.getInvoiceNo() != null) {
             Optional<Invoice> existing;
             if (SecurityUtils.isSuperAdmin()) {
                 existing = invoiceRepository.findByInvoiceNoAndClientId(
                     invoice.getInvoiceNo(), invoice.getClientId()
                 );
             } else {
                 existing = invoiceRepository.findByInvoiceNoAndClientIdAndOrgId(
                     invoice.getInvoiceNo(), invoice.getClientId(), invoice.getOrgId()
                 );
             }
             
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
}
