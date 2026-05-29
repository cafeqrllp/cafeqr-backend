package com.restaurant.pos.purchasing.service;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.purchasing.domain.*;
import com.restaurant.pos.purchasing.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PurchasingService {

    private final CustomerRepository customerRepository;
    private final VendorRepository vendorRepository;
    private final CurrencyRepository currencyRepository;
    private final PricelistRepository pricelistRepository;
    private final BranchContextService branchContext;

    // ═══════════════════════════════════════════════════════════════════════
    // CUSTOMERS (GLOBAL — client-scoped, not branch-scoped)
    // ═══════════════════════════════════════════════════════════════════════

    public List<Customer> getCustomers() {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (SecurityUtils.isSuperAdmin()) {
            return customerRepository.findByClientIdOrderByNameAsc(tenantId);
        }
        return customerRepository.findByClientIdAndOrgIdOrGlobalOrderByNameAsc(tenantId, TenantContext.getCurrentOrg());
    }

    public Customer getCustomer(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (SecurityUtils.isSuperAdmin()) {
            return customerRepository.findByIdAndClientId(id, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        }
        return customerRepository.findByIdAndClientIdAndOrgIdOrGlobal(id, tenantId, TenantContext.getCurrentOrg())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
    }

    @Transactional
    public Customer saveCustomer(Customer customer) {
        customer.setClientId(TenantContext.getCurrentTenant());
        // Customers are GLOBAL (client-scoped, not branch-scoped)
        customer.setOrgId(null);
        customer.setPhone(normalizePhone(customer.getPhone()));
        ensureUniqueCustomerPhone(customer.getClientId(), customer.getPhone(), null);
        return customerRepository.save(customer);
    }

    @Transactional
    public Customer updateCustomer(UUID id, Customer updates) {
        Customer existing = getCustomer(id);
        existing.setName(updates.getName());
        String normalizedPhone = normalizePhone(updates.getPhone());
        ensureUniqueCustomerPhone(existing.getClientId(), normalizedPhone, existing.getId());
        existing.setPhone(normalizedPhone);
        existing.setEmail(updates.getEmail());
        existing.setAddress(updates.getAddress());
        existing.setGstNumber(updates.getGstNumber());
        existing.setCustomerCategory(updates.getCustomerCategory());
        existing.setLoyaltyPoints(updates.getLoyaltyPoints());
        existing.setCreditLimit(updates.getCreditLimit());
        existing.setOpeningBalance(updates.getOpeningBalance());
        existing.setPricelistId(updates.getPricelistId());
        existing.setIsactive(updates.getIsactive());
        return customerRepository.save(existing);
    }

    @Transactional
    public void deleteCustomer(UUID id) {
        Customer customer = getCustomer(id);
        customerRepository.delete(customer);
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String normalized = phone.trim().replaceAll("[\\s()\\-]", "");
        return normalized.isBlank() ? null : normalized;
    }

    private void ensureUniqueCustomerPhone(UUID clientId, String phone, UUID existingCustomerId) {
        if (clientId == null || phone == null || phone.isBlank()) {
            return;
        }
        boolean duplicate = existingCustomerId == null
                ? customerRepository.existsByClientIdAndPhone(clientId, phone)
                : customerRepository.existsByClientIdAndPhoneAndIdNot(clientId, phone, existingCustomerId);
        if (duplicate) {
            throw new BusinessException("A customer with this phone number already exists");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // VENDORS
    // ═══════════════════════════════════════════════════════════════════════

    public List<Vendor> getVendors() {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (SecurityUtils.isSuperAdmin()) {
            return vendorRepository.findByClientIdOrderByNameAsc(tenantId);
        }
        return vendorRepository.findByClientIdAndOrgIdOrGlobalOrderByNameAsc(tenantId, TenantContext.getCurrentOrg());
    }

    public Vendor getVendor(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (SecurityUtils.isSuperAdmin()) {
            return vendorRepository.findByIdAndClientId(id, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor not found"));
        }
        return vendorRepository.findByIdAndClientIdAndOrgIdOrGlobal(id, tenantId, TenantContext.getCurrentOrg())
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found"));
    }

    @Transactional
    public Vendor saveVendor(Vendor vendor) {
        vendor.setClientId(TenantContext.getCurrentTenant());
        vendor.setOrgId(branchContext.requireWriteOrgId(vendor.getOrgId()));
        return vendorRepository.save(vendor);
    }

    @Transactional
    public Vendor updateVendor(UUID id, Vendor updates) {
        Vendor existing = getVendor(id);
        existing.setName(updates.getName());
        existing.setContactPerson(updates.getContactPerson());
        existing.setPhone(updates.getPhone());
        existing.setEmail(updates.getEmail());
        existing.setAddress(updates.getAddress());
        existing.setGstin(updates.getGstin());
        existing.setOpeningBalance(updates.getOpeningBalance());
        existing.setCreditLimit(updates.getCreditLimit());
        existing.setPricelistId(updates.getPricelistId());
        existing.setIsactive(updates.getIsactive());
        return vendorRepository.save(existing);
    }

    @Transactional
    public void deleteVendor(UUID id) {
        Vendor vendor = getVendor(id);
        vendorRepository.delete(vendor);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CURRENCIES
    // ═══════════════════════════════════════════════════════════════════════

    public List<Currency> getCurrencies() {
        UUID tenantId = TenantContext.getCurrentTenant();
        return currencyRepository.findByClientIdOrderByCodeAsc(tenantId);
    }

    @Transactional
    public Currency saveCurrency(Currency currency) {
        currency.setClientId(TenantContext.getCurrentTenant());
        currency.setOrgId(branchContext.requireWriteOrgId(currency.getOrgId()));
        if (Boolean.TRUE.equals(currency.getIsDefault())) {
            clearDefaultCurrencies(currency.getClientId());
        }
        return currencyRepository.save(currency);
    }

    @Transactional
    public Currency updateCurrency(UUID id, Currency updates) {
        UUID tenantId = TenantContext.getCurrentTenant();
        Currency existing = currencyRepository.findByIdAndClientId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Currency not found"));
        existing.setCode(updates.getCode());
        existing.setSymbol(updates.getSymbol());
        existing.setName(updates.getName());
        existing.setDescription(updates.getDescription());
        existing.setExchangeRate(updates.getExchangeRate());
        existing.setDecimalPlaces(updates.getDecimalPlaces());
        existing.setCountryCode(updates.getCountryCode());
        if (Boolean.TRUE.equals(updates.getIsDefault())) {
            clearDefaultCurrencies(tenantId);
        }
        existing.setIsDefault(updates.getIsDefault());
        existing.setIsactive(updates.getIsactive());
        return currencyRepository.save(existing);
    }

    private void clearDefaultCurrencies(UUID clientId) {
        List<Currency> defaults = currencyRepository.findByClientIdAndIsDefaultTrue(clientId);
        defaults.forEach(c -> c.setIsDefault(false));
        currencyRepository.saveAll(defaults);
    }

    @Transactional
    public void deleteCurrency(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenant();
        Currency currency = currencyRepository.findByIdAndClientId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Currency not found"));
        currencyRepository.delete(currency);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PRICELISTS
    // ═══════════════════════════════════════════════════════════════════════

    public List<Pricelist> getPricelists() {
        UUID tenantId = TenantContext.getCurrentTenant();
        return pricelistRepository.findByClientIdOrderByNameAsc(tenantId);
    }

    public List<Pricelist> getPricelistsByType(String type) {
        UUID tenantId = TenantContext.getCurrentTenant();
        return pricelistRepository.findByClientIdAndPricelistTypeOrderByNameAsc(tenantId, type);
    }

    @Transactional
    public Pricelist savePricelist(Pricelist pricelist) {
        pricelist.setClientId(TenantContext.getCurrentTenant());
        pricelist.setOrgId(branchContext.requireWriteOrgId(pricelist.getOrgId()));
        if (Boolean.TRUE.equals(pricelist.getIsDefault())) {
            clearDefaultPricelists(pricelist.getClientId(), pricelist.getPricelistType());
        }
        // Ensure bidirectional mapping for versions
        if (pricelist.getVersions() != null) {
            pricelist.getVersions().forEach(v -> v.setPricelist(pricelist));
        }
        return pricelistRepository.save(pricelist);
    }

    @Transactional
    public Pricelist updatePricelist(UUID id, Pricelist updates) {
        UUID tenantId = TenantContext.getCurrentTenant();
        Pricelist existing = pricelistRepository.findByIdAndClientId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Pricelist not found"));
        existing.setName(updates.getName());
        existing.setDescription(updates.getDescription());
        existing.setPricelistType(updates.getPricelistType());
        existing.setCurrencyId(updates.getCurrencyId());
        existing.setDiscountPercentage(updates.getDiscountPercentage());
        existing.setMarkupPercentage(updates.getMarkupPercentage());
        if (Boolean.TRUE.equals(updates.getIsDefault())) {
            clearDefaultPricelists(tenantId, existing.getPricelistType());
        }
        existing.setIsDefault(updates.getIsDefault());
        existing.setIsactive(updates.getIsactive());

        return pricelistRepository.save(existing);
    }

    private void clearDefaultPricelists(UUID clientId, String pricelistType) {
        List<Pricelist> defaults = pricelistRepository.findByClientIdAndPricelistTypeAndIsDefaultTrue(clientId, pricelistType);
        defaults.forEach(p -> p.setIsDefault(false));
        pricelistRepository.saveAll(defaults);
    }

    @Transactional
    public void deletePricelist(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenant();
        Pricelist pricelist = pricelistRepository.findByIdAndClientId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Pricelist not found"));
        pricelistRepository.delete(pricelist);
    }
}
