package com.restaurant.pos.credit.common;

import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.credit.domain.CreditCustomer;
import com.restaurant.pos.credit.repository.CreditCustomerRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.purchasing.domain.Vendor;
import com.restaurant.pos.purchasing.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared utilities for the Credit module — used by both
 * {@link com.restaurant.pos.credit.command.CreditCommandService} and
 * {@link com.restaurant.pos.credit.query.CreditQueryService}.
 */
@Component
@RequiredArgsConstructor
public class CreditGuard {

    private final SystemConfigurationService configurationService;
    private final CreditCustomerRepository creditCustomerRepository;
    private final VendorRepository vendorRepository;
    private final OrderRepository orderRepository;

    // ── Configuration ────────────────────────────────────────────────────────

    public boolean isCreditEnabled() {
        try {
            ConfigurationDto config = configurationService.getConfiguration();
            return config != null && config.isCreditEnabled();
        } catch (Exception ex) {
            return false;
        }
    }

    public void ensureCreditEnabled() {
        ConfigurationDto config = configurationService.getConfiguration();
        if (config == null || !config.isCreditEnabled()) {
            throw new BusinessException("Credit Ledger is not enabled for this organization");
        }
    }

    // ── Tenant ───────────────────────────────────────────────────────────────

    public UUID requireClient() {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            throw new BusinessException("Client context is required");
        }
        return clientId;
    }

    // ── Entity resolution ────────────────────────────────────────────────────

    public CreditCustomer getCreditCustomer(UUID id, UUID clientId) {
        if (id == null) {
            throw new BusinessException("Credit customer is required");
        }
        return creditCustomerRepository.findByIdAndClientId(id, clientId)
                .filter(customer -> !"N".equalsIgnoreCase(customer.getIsactive()))
                .orElseThrow(() -> new ResourceNotFoundException("Credit customer not found"));
    }

    public CreditCustomer getCreditCustomerForUpdate(UUID id, UUID clientId) {
        if (id == null) {
            throw new BusinessException("Credit customer is required");
        }
        return creditCustomerRepository.findByIdAndClientIdForUpdate(id, clientId)
                .filter(customer -> !"N".equalsIgnoreCase(customer.getIsactive()))
                .orElseThrow(() -> new ResourceNotFoundException("Credit customer not found"));
    }


    public Vendor getVendor(UUID id, UUID clientId) {
        if (id == null) {
            throw new BusinessException("Vendor is required");
        }
        return vendorRepository.findByIdAndClientId(id, clientId)
                .filter(vendor -> !"N".equalsIgnoreCase(vendor.getIsactive()))
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found"));
    }

    public Vendor getVendorForUpdate(UUID id, UUID clientId) {
        if (id == null) {
            throw new BusinessException("Vendor is required");
        }
        return vendorRepository.findByIdAndClientIdForUpdate(id, clientId)
                .filter(vendor -> !"N".equalsIgnoreCase(vendor.getIsactive()))
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found"));
    }


    public Order resolveOrder(UUID orderId) {
        if (orderId == null) {
            return null;
        }
        return orderRepository.findById(orderId).orElse(null);
    }

    // ── Normalizers ──────────────────────────────────────────────────────────

    public BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    public String normalizePaymentMethod(String value) {
        if (value == null || value.isBlank()) {
            return "CASH";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public String normalizeStatus(String status) {
        String normalized = status == null || status.isBlank() ? "ACTIVE" : status.trim().toUpperCase(Locale.ROOT);
        return "SUSPENDED".equals(normalized) ? "SUSPENDED" : "ACTIVE";
    }

    public String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String normalized = phone.trim().replaceAll("[\\s()\\-]", "");
        return normalized.isBlank() ? null : normalized;
    }

    public String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public boolean isVoid(String status) {
        return status != null && ("VOID".equalsIgnoreCase(status) || "VOIDED".equalsIgnoreCase(status));
    }

    public boolean isVendor(String partnerType) {
        return "VENDOR".equalsIgnoreCase(partnerType);
    }
}
