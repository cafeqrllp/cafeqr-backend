package com.restaurant.pos.common.service;

import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.entity.SystemConfiguration;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.repository.SystemConfigurationRepository;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.tenant.UserContext;
import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.purchasing.repository.CurrencyRepository;
import com.restaurant.pos.subscription.domain.ModuleName;
import com.restaurant.pos.subscription.domain.ClientSubscriptionModule;
import com.restaurant.pos.subscription.repository.ClientSubscriptionModuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Collections;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SystemConfigurationService {

    private final SystemConfigurationRepository repository;
    private final ObjectMapper objectMapper;
    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;
    private final CurrencyRepository currencyRepository;
    private final ClientSubscriptionModuleRepository clientSubscriptionModuleRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfigurationDto getConfiguration() {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            return mapToDto(getGlobalConfiguration());
        }
        // Branch-aware resolution: try branch config first, then client default
        UUID orgId = SecurityUtils.isSuperAdmin()
                ? TenantContext.getCurrentOrg()
                : UserContext.getContext().getOrgId();
        SystemConfiguration config = resolveConfiguration(clientId, orgId);
        return mapToDto(config);
    }

    @Transactional
    public ConfigurationDto updateConfiguration(ConfigurationDto dto) {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            throw new BusinessException("Client context is required to update system configuration");
        }

        UUID orgId = SecurityUtils.isSuperAdmin()
                ? TenantContext.getCurrentOrg()
                : UserContext.getContext().getOrgId();
        if (orgId != null) {
            return updateBranchConfiguration(orgId, dto);
        }

        validateModuleSubscriptions(clientId, null, dto);

        SystemConfiguration config = getOrCreateTenantConfiguration(clientId);

        updateEntityFromDto(config, dto);
        SystemConfiguration saved = repository.save(config);
        log.info("System configuration updated successfully. clientId={}", clientId);
        return mapToDto(saved);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfigurationDto getConfigurationForClientAndBranch(UUID clientId, UUID orgId) {
        if (clientId == null) {
            return mapToDto(getGlobalConfiguration());
        }
        SystemConfiguration config = resolveConfiguration(clientId, orgId);
        return mapToDto(config);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfigurationDto getConfigurationForClient(UUID clientId) {
        SystemConfiguration config = clientId == null
                ? getGlobalConfiguration()
                : getOrCreateTenantConfiguration(clientId);
        return mapToDto(config);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BRANCH-LEVEL CONFIG OVERRIDES
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get configuration for a specific branch.
     * Returns the branch-level override if it exists, otherwise the client default.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfigurationDto getConfigurationForBranch(UUID orgId) {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            throw new BusinessException("Client context is required");
        }
        SystemConfiguration config = resolveConfiguration(clientId, orgId);
        ConfigurationDto dto = mapToDto(config);
        dto.setBranchOverride(config.getOrgId() != null);
        return dto;
    }

    /**
     * Returns the effective (resolved) configuration that a branch actually uses at
     * runtime.
     * Branch override is merged on top of client default — identical to what POS
     * uses.
     * Also sets branchOverride=true if a custom override row exists.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfigurationDto getEffectiveConfigurationForBranch(UUID orgId) {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            throw new BusinessException("Client context is required");
        }
        // Check if a branch-specific row exists
        boolean hasBranchOverride = repository.findFirstByClientIdAndOrgId(clientId, orgId).isPresent();
        // resolveConfiguration already falls back to client default if no branch row
        SystemConfiguration effective = resolveConfiguration(clientId, orgId);
        ConfigurationDto dto = mapToDto(effective);
        dto.setBranchOverride(hasBranchOverride);
        return dto;
    }

    /**
     * Save a branch-level configuration override (full copy strategy).
     * Creates a new branch config row if none exists, copying from the client
     * default first.
     */
    @Transactional
    public ConfigurationDto updateBranchConfiguration(UUID orgId, ConfigurationDto dto) {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            throw new BusinessException("Client context is required");
        }
        if (orgId == null) {
            throw new BusinessException("Branch ID is required for branch-level configuration");
        }

        validateModuleSubscriptions(clientId, orgId, dto);

        SystemConfiguration branchConfig = repository.findFirstByClientIdAndOrgId(clientId, orgId)
                .orElseGet(() -> {
                    // Full-copy strategy: clone the client default into a new branch row
                    SystemConfiguration clientDefault = getOrCreateTenantConfiguration(clientId);
                    SystemConfiguration copy = cloneConfiguration(clientDefault);
                    copy.setId(null);
                    copy.setOrgId(orgId);
                    return copy;
                });

        updateEntityFromDto(branchConfig, dto);
        SystemConfiguration saved = repository.save(branchConfig);
        log.info("Branch configuration updated. clientId={}, orgId={}", clientId, orgId);
        ConfigurationDto result = mapToDto(saved);
        result.setBranchOverride(true);
        return result;
    }

    /**
     * Delete the branch-level override, reverting the branch to the client default.
     */
    @Transactional
    public void deleteBranchConfiguration(UUID orgId) {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            throw new BusinessException("Client context is required");
        }
        if (orgId == null) {
            throw new BusinessException("Branch ID is required");
        }
        repository.deleteByClientIdAndOrgId(clientId, orgId);
        log.info("Branch configuration override deleted (reverted to client default). clientId={}, orgId={}", clientId,
                orgId);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RESOLUTION LOGIC
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Resolution order:
     * 1. Branch-level config (client_id + org_id) — if exists
     * 2. Client-level config (client_id, org_id IS NULL) — fallback
     * 3. Global default (both NULL) — last resort
     */
    private SystemConfiguration resolveConfiguration(UUID clientId, UUID orgId) {
        if (orgId != null) {
            return repository.findFirstByClientIdAndOrgId(clientId, orgId)
                    .orElseGet(() -> getOrCreateTenantConfiguration(clientId));
        }
        return getOrCreateTenantConfiguration(clientId);
    }

    private SystemConfiguration getOrCreateTenantConfiguration(UUID clientId) {
        return repository.findFirstByClientIdAndOrgIdIsNull(clientId)
                .orElseGet(() -> createDefaultConfig(clientId));
    }

    private SystemConfiguration getGlobalConfiguration() {
        return repository.findFirstByClientIdIsNullAndOrgIdIsNullOrderByCreatedAtAsc()
                .orElseGet(() -> createDefaultConfig(null));
    }

    private SystemConfiguration createDefaultConfig(UUID clientId) {
        SystemConfiguration config = SystemConfiguration.builder()
                .clientId(clientId)
                .orgId(null)
                .qrOrderingEnabled(true)
                .sendToKitchenEnabled(true)
                .customerAgeEnabled(false)
                .posProductListingEnabled(true)
                .discountEnabled(true)
                .purchaseEnabled(true)
                .defaultBillingUiMode("standard")
                .offlineSyncEnabled(false)
                .offlineSyncInterval(60)
                .offlineLeaseBlockSize(100)
                .offlineFailOpenPayments(false)
                .offlineLocalEncryption(false)
                .taxEnabled(false)
                .taxLabelGlobal("GST")
                .taxRatesJson("[]")
                .pricesIncludeTax(false)
                .taxSplitEnabled(true)
                .currencySymbol("\u20b9")
                .currencyPosition("before")
                .roundOffMode("automatic")
                .roundOffAutoFactor(java.math.BigDecimal.ONE)
                .roundOffManualLimit(java.math.BigDecimal.TEN)
                .build();
        return repository.save(config);
    }

    private SystemConfiguration cloneConfiguration(SystemConfiguration source) {
        return SystemConfiguration.builder()
                .clientId(source.getClientId())
                .orgId(source.getOrgId())
                .onlinePaymentEnabled(source.isOnlinePaymentEnabled())
                .menuImagesEnabled(source.isMenuImagesEnabled())
                .creditEnabled(source.isCreditEnabled())
                .creditAllocationMode(normalizeCreditAllocationMode(source.getCreditAllocationMode()))
                .tableManagementEnabled(source.isTableManagementEnabled())
                .qrOrderingEnabled(source.isQrOrderingEnabled())
                .inventoryEnabled(source.isInventoryEnabled())
                .purchaseEnabled(source.isPurchaseEnabled())
                .productionEnabled(source.isProductionEnabled())
                .customersEnabled(source.isCustomersEnabled())
                .loyaltyEnabled(source.isLoyaltyEnabled())
                .sendToKitchenEnabled(source.isSendToKitchenEnabled())
                .onlineDeliveryEnabled(source.isOnlineDeliveryEnabled())
                .allowMultipleCustomersPerOrder(source.isAllowMultipleCustomersPerOrder())
                .customerAgeEnabled(source.isCustomerAgeEnabled())
                .posProductListingEnabled(source.isPosProductListingEnabled())
                .discountEnabled(source.isDiscountEnabled())
                .defaultBillingUiMode(source.getDefaultBillingUiMode())
                .offlineSyncEnabled(source.isOfflineSyncEnabled())
                .offlineSyncInterval(source.getOfflineSyncInterval())
                .offlineLeaseBlockSize(source.getOfflineLeaseBlockSize())
                .offlineFailOpenPayments(source.isOfflineFailOpenPayments())
                .offlineLocalEncryption(source.isOfflineLocalEncryption())
                .roundOffEnabled(source.isRoundOffEnabled())
                .roundOffMode(source.getRoundOffMode())
                .roundOffAutoFactor(source.getRoundOffAutoFactor())
                .roundOffManualLimit(source.getRoundOffManualLimit())
                .taxEnabled(source.isTaxEnabled())
                .taxLabelGlobal(source.getTaxLabelGlobal())
                .taxRatesJson(source.getTaxRatesJson())
                .taxDefaultId(source.getTaxDefaultId())
                .pricesIncludeTax(source.isPricesIncludeTax())
                .taxSplitEnabled(source.isTaxSplitEnabled())
                .currencySymbol(source.getCurrencySymbol())
                .currencyPosition(source.getCurrencyPosition())
                .billFooter(source.getBillFooter())
                .printLogoBitmap(source.getPrintLogoBitmap())
                .printLogoCols(source.getPrintLogoCols())
                .printLogoRows(source.getPrintLogoRows())
                .paperMm(source.getPaperMm())
                .printCols(source.getPrintCols())
                .printLeftMarginDots(source.getPrintLeftMarginDots())
                .printRightMarginDots(source.getPrintRightMarginDots())
                .printAutoCut(source.isPrintAutoCut())
                .printWinListUrl(source.getPrintWinListUrl())
                .printWinPostUrl(source.getPrintWinPostUrl())
                .build();
    }

    private String resolveLogoUrl(UUID clientId, UUID orgId) {
        String logoUrl = null;
        if (clientId != null) {
            var clientOpt = clientRepository.findById(clientId);
            if (clientOpt.isPresent()) {
                logoUrl = clientOpt.get().getLogoUrl();
            }
        }
        if (orgId != null) {
            var orgOpt = organizationRepository.findById(orgId);
            if (orgOpt.isPresent() && orgOpt.get().getLogoUrl() != null && !orgOpt.get().getLogoUrl().isBlank()) {
                logoUrl = orgOpt.get().getLogoUrl();
            }
        }
        return logoUrl;
    }

    private ConfigurationDto mapToDto(SystemConfiguration entity) {
        UUID orgId = entity.getOrgId();
        if (orgId == null) {
            try {
                orgId = SecurityUtils.isSuperAdmin()
                        ? TenantContext.getCurrentOrg()
                        : (UserContext.getContext() != null ? UserContext.getContext().getOrgId() : null);
            } catch (Exception e) {
                // Ignore context errors in background tasks or tests
            }
        }
        String resolvedLogoUrl = resolveLogoUrl(entity.getClientId(), orgId);

        Integer decimalPlaces = 2;
        String currencySymbol = "";

        if (entity.getClientId() != null) {
            try {
                Optional<com.restaurant.pos.purchasing.domain.Currency> defaultCurrencyOpt = Optional.empty();
                if (orgId != null) {
                    defaultCurrencyOpt = currencyRepository
                            .findByClientIdAndOrgIdAndIsDefaultTrue(entity.getClientId(), orgId)
                            .stream()
                            .findFirst();
                }
                if (defaultCurrencyOpt.isEmpty()) {
                    defaultCurrencyOpt = currencyRepository.findByClientIdAndIsDefaultTrue(entity.getClientId())
                            .stream()
                            .findFirst();
                }

                if (defaultCurrencyOpt.isPresent()) {
                    var currency = defaultCurrencyOpt.get();
                    decimalPlaces = currency.getDecimalPlaces() != null ? currency.getDecimalPlaces() : 2;
                    currencySymbol = currency.getSymbol() != null ? currency.getSymbol() : "";
                }
            } catch (Exception e) {
                log.warn("Failed to fetch default currency details for client {}, org {}", entity.getClientId(), orgId,
                        e);
            }
        }

        Client client = null;
        if (entity.getClientId() != null) {
            client = clientRepository.findById(entity.getClientId()).orElse(null);
        }

        com.restaurant.pos.client.domain.Organization org = null;
        if (orgId != null) {
            org = organizationRepository.findById(orgId).orElse(null);
        }

        String resolvedRestaurantName = org != null ? org.getName() : (client != null ? client.getName() : null);
        String resolvedPhone = org != null ? org.getPhone() : (client != null ? client.getPhone() : null);
        String resolvedGstin = (org != null && org.getGstin() != null && !org.getGstin().isBlank()) 
                ? org.getGstin() 
                : (client != null ? client.getGstNumber() : null);
        String resolvedFssai = client != null ? client.getFssaiNumber() : null;
        String resolvedAddress = org != null ? org.getAddress() : (client != null ? client.getAddress() : null);
        String resolvedPincode = org != null ? org.getPinCode() : (client != null ? client.getPinCode() : null);
        String resolvedTimezone = org != null ? org.getTimezone() : (client != null ? client.getTimezone() : null);

        return ConfigurationDto.builder()
                .onlinePaymentEnabled(entity.isOnlinePaymentEnabled())
                .menuImagesEnabled(isFeatureEnabled(entity.getClientId(), orgId, ModuleName.MENU_IMAGES, entity.isMenuImagesEnabled()))
                .creditEnabled(isFeatureEnabled(entity.getClientId(), orgId, ModuleName.CREDIT_LEDGER, entity.isCreditEnabled()))
                .creditAllocationMode(normalizeCreditAllocationMode(entity.getCreditAllocationMode()))
                .tableManagementEnabled(entity.isTableManagementEnabled())
                .qrOrderingEnabled(isFeatureEnabled(entity.getClientId(), orgId, ModuleName.TABLE_QR, entity.isQrOrderingEnabled()))
                .inventoryEnabled(isFeatureEnabled(entity.getClientId(), orgId, ModuleName.INVENTORY, entity.isInventoryEnabled()))
                .purchaseEnabled(isFeatureEnabled(entity.getClientId(), orgId, ModuleName.INVENTORY, entity.isPurchaseEnabled()))
                .productionEnabled(isFeatureEnabled(entity.getClientId(), orgId, ModuleName.INVENTORY, entity.isProductionEnabled()))
                .customersEnabled(isFeatureEnabled(entity.getClientId(), orgId, ModuleName.CRM, entity.isCustomersEnabled()))
                .loyaltyEnabled(isFeatureEnabled(entity.getClientId(), orgId, ModuleName.CRM, entity.isLoyaltyEnabled()))
                .sendToKitchenEnabled(isFeatureEnabled(entity.getClientId(), orgId, ModuleName.KOT, entity.isSendToKitchenEnabled()))
                .onlineDeliveryEnabled(isFeatureEnabled(entity.getClientId(), orgId, ModuleName.ONLINE_DELIVERY, entity.isOnlineDeliveryEnabled()))
                .allowMultipleCustomersPerOrder(entity.isAllowMultipleCustomersPerOrder())
                .customerAgeEnabled(entity.isCustomerAgeEnabled())
                .posProductListingEnabled(entity.isPosProductListingEnabled())
                .discountEnabled(entity.isDiscountEnabled())
                .defaultBillingUiMode(entity.getDefaultBillingUiMode())
                .offlineSyncEnabled(entity.isOfflineSyncEnabled())
                .offlineSyncInterval(entity.getOfflineSyncInterval())
                .offlineLeaseBlockSize(entity.getOfflineLeaseBlockSize())
                .offlineFailOpenPayments(entity.isOfflineFailOpenPayments())
                .offlineLocalEncryption(entity.isOfflineLocalEncryption())
                .roundOffEnabled(entity.isRoundOffEnabled())
                .roundOffMode(entity.getRoundOffMode())
                .roundOffAutoFactor(entity.getRoundOffAutoFactor())
                .roundOffManualLimit(entity.getRoundOffManualLimit())
                .taxEnabled(entity.isTaxEnabled())
                .taxLabelGlobal(entity.getTaxLabelGlobal())
                .taxRates(parseTaxRates(entity.getTaxRatesJson()))
                .taxDefaultId(entity.getTaxDefaultId())
                .pricesIncludeTax(entity.isPricesIncludeTax())
                .taxSplitEnabled(entity.isTaxSplitEnabled())
                .currencySymbol(currencySymbol)
                .currencyPosition(entity.getCurrencyPosition())
                .currencyDecimalPlaces(decimalPlaces)
                .billFooter(entity.getBillFooter())
                .printLogoBitmap(entity.getPrintLogoBitmap())
                .printLogoCols(entity.getPrintLogoCols())
                .printLogoRows(entity.getPrintLogoRows())
                .paperMm(entity.getPaperMm())
                .printCols(entity.getPrintCols())
                .printLeftMarginDots(entity.getPrintLeftMarginDots())
                .printRightMarginDots(entity.getPrintRightMarginDots())
                .printAutoCut(entity.isPrintAutoCut())
                .printWinListUrl(entity.getPrintWinListUrl())
                .printWinPostUrl(entity.getPrintWinPostUrl())
                .logoUrl(resolvedLogoUrl)
                .restaurantName(resolvedRestaurantName)
                .phone(resolvedPhone)
                .gstin(resolvedGstin)
                .fssaiLicense(resolvedFssai)
                .shippingAddressLine1(resolvedAddress)
                .shippingPincode(resolvedPincode)
                .timezone(resolvedTimezone)
                .build();
    }

    private void updateEntityFromDto(SystemConfiguration entity, ConfigurationDto dto) {
        entity.setOnlinePaymentEnabled(dto.isOnlinePaymentEnabled());
        entity.setMenuImagesEnabled(dto.isMenuImagesEnabled());
        entity.setCreditEnabled(dto.isCreditEnabled());
        entity.setCreditAllocationMode(normalizeCreditAllocationMode(dto.getCreditAllocationMode()));
        entity.setTableManagementEnabled(dto.isTableManagementEnabled());
        entity.setQrOrderingEnabled(dto.isQrOrderingEnabled());
        entity.setInventoryEnabled(dto.isInventoryEnabled());
        entity.setPurchaseEnabled(dto.isPurchaseEnabled());
        entity.setProductionEnabled(dto.isProductionEnabled());
        entity.setCustomersEnabled(dto.isCustomersEnabled());
        entity.setLoyaltyEnabled(dto.isLoyaltyEnabled());
        entity.setSendToKitchenEnabled(dto.isSendToKitchenEnabled());
        entity.setOnlineDeliveryEnabled(dto.isOnlineDeliveryEnabled());
        entity.setAllowMultipleCustomersPerOrder(dto.isAllowMultipleCustomersPerOrder());
        entity.setCustomerAgeEnabled(dto.isCustomerAgeEnabled());
        entity.setPosProductListingEnabled(dto.isPosProductListingEnabled());
        entity.setDiscountEnabled(dto.isDiscountEnabled());
        if (dto.getDefaultBillingUiMode() != null) entity.setDefaultBillingUiMode(dto.getDefaultBillingUiMode());
        entity.setOfflineSyncEnabled(dto.isOfflineSyncEnabled());
        entity.setOfflineSyncInterval(dto.getOfflineSyncInterval());
        entity.setOfflineFailOpenPayments(dto.isOfflineFailOpenPayments());
        entity.setOfflineLocalEncryption(dto.isOfflineLocalEncryption());
        entity.setRoundOffEnabled(dto.isRoundOffEnabled());
        entity.setRoundOffMode(dto.getRoundOffMode());
        entity.setRoundOffAutoFactor(dto.getRoundOffAutoFactor());
        entity.setRoundOffManualLimit(dto.getRoundOffManualLimit());
        entity.setTaxEnabled(dto.isTaxEnabled());
        if (dto.getTaxLabelGlobal() != null) entity.setTaxLabelGlobal(dto.getTaxLabelGlobal());
        entity.setTaxRatesJson(serializeTaxRates(dto.getTaxRates()));
        entity.setTaxDefaultId(dto.getTaxDefaultId());
        entity.setPricesIncludeTax(dto.isPricesIncludeTax());
        entity.setTaxSplitEnabled(dto.isTaxSplitEnabled());
        if (dto.getCurrencySymbol() != null) entity.setCurrencySymbol(dto.getCurrencySymbol());
        if (dto.getCurrencyPosition() != null) entity.setCurrencyPosition(dto.getCurrencyPosition());

        // Receipt Customization
        entity.setBillFooter(dto.getBillFooter());
        entity.setPrintLogoBitmap(dto.getPrintLogoBitmap());
        entity.setPrintLogoCols(dto.getPrintLogoCols());
        entity.setPrintLogoRows(dto.getPrintLogoRows());

        // Hardware & Paper
        if (dto.getPaperMm() != null) entity.setPaperMm(dto.getPaperMm());
        if (dto.getPrintCols() != null) entity.setPrintCols(dto.getPrintCols());
        if (dto.getPrintLeftMarginDots() != null) entity.setPrintLeftMarginDots(dto.getPrintLeftMarginDots());
        if (dto.getPrintRightMarginDots() != null)
            entity.setPrintRightMarginDots(dto.getPrintRightMarginDots());
        entity.setPrintAutoCut(dto.isPrintAutoCut());
        if (dto.getPrintWinListUrl() != null) entity.setPrintWinListUrl(dto.getPrintWinListUrl());
        if (dto.getPrintWinPostUrl() != null) entity.setPrintWinPostUrl(dto.getPrintWinPostUrl());
    }

    private List<Object> parseTaxRates(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>(){});
        } catch (Exception e) {
            log.error("Failed to parse tax rates JSON", e);
            return Collections.emptyList();
        }
    }

    private String serializeTaxRates(List<Object> rates) {
        if (rates == null) return "[]";
        try {
            return objectMapper.writeValueAsString(rates);
        } catch (Exception e) {
            log.error("Failed to serialize tax rates", e);
            return "[]";
        }
    }

    private String normalizeCreditAllocationMode(String value) {
        if (value == null || value.isBlank()) {
            return "OLDEST_FIRST";
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return "MANUAL".equals(normalized) ? "MANUAL" : "OLDEST_FIRST";
    }

    private void validateModuleSubscriptions(UUID clientId, UUID orgId, ConfigurationDto dto) {
        // 1. Check if client has a TRIAL subscription that is still active
        Optional<Client> clientOpt = clientRepository.findById(clientId);
        if (clientOpt.isPresent()) {
            Client client = clientOpt.get();
            String status = client.getSubscriptionStatus();
            if (status != null) {
                status = status.trim().toUpperCase();
                if ("TRIAL".equals(status) && client.getSubscriptionExpiryDate() != null && client.getSubscriptionExpiryDate().isAfter(java.time.LocalDateTime.now())) {
                    return; // Free trial gives access to all features
                }
            }
        }

        // 2. Map configuration features to module enums and check subscriptions
        if (dto.isSendToKitchenEnabled() && !isModuleActive(clientId, orgId, ModuleName.KOT)) {
            throw new BusinessException("Subscription required: Kitchen Order Ticket (KOT) is not active. Please visit the billing center.");
        }
        if ((dto.isInventoryEnabled() || dto.isPurchaseEnabled()) && !isModuleActive(clientId, orgId, ModuleName.INVENTORY)) {
            throw new BusinessException("Subscription required: Inventory & Purchase ERP is not active. Please visit the billing center.");
        }
        if (dto.isCreditEnabled() && !isModuleActive(clientId, orgId, ModuleName.CREDIT_LEDGER)) {
            throw new BusinessException("Subscription required: Credit Ledger (Udhaar) is not active. Please visit the billing center.");
        }
        if ((dto.isCustomersEnabled() || dto.isLoyaltyEnabled()) && !isModuleActive(clientId, orgId, ModuleName.CRM)) {
            throw new BusinessException("Subscription required: Customer CRM & Loyalty is not active. Please visit the billing center.");
        }
        if (dto.isQrOrderingEnabled() && !isModuleActive(clientId, orgId, ModuleName.TABLE_QR)) {
            throw new BusinessException("Subscription required: Table QR Ordering is not active. Please visit the billing center.");
        }
        if (dto.isMenuImagesEnabled() && !isModuleActive(clientId, orgId, ModuleName.MENU_IMAGES)) {
            throw new BusinessException("Subscription required: Menu Images module is not active. Please visit the billing center.");
        }
        if (dto.isOnlineDeliveryEnabled() && !isModuleActive(clientId, orgId, ModuleName.ONLINE_DELIVERY)) {
            throw new BusinessException("Subscription required: Online Delivery module is not active. Please visit the billing center.");
        }
    }

    private boolean isFeatureEnabled(UUID clientId, UUID orgId, ModuleName module, boolean configValue) {
        if (!configValue) {
            return false;
        }
        if (clientId == null) {
            return true;
        }
        Optional<Client> clientOpt = clientRepository.findById(clientId);
        if (clientOpt.isPresent()) {
            Client client = clientOpt.get();
            String status = client.getSubscriptionStatus();
            if (status != null) {
                status = status.trim().toUpperCase();
                if ("TRIAL".equals(status) && client.getSubscriptionExpiryDate() != null && client.getSubscriptionExpiryDate().isAfter(java.time.LocalDateTime.now())) {
                    return true;
                }
            }
        }
        return isModuleActive(clientId, orgId, module);
    }

    public boolean isModuleActive(UUID clientId, UUID orgId, ModuleName moduleName) {
        List<ClientSubscriptionModule> activeModules = clientSubscriptionModuleRepository.findByClientId(clientId);
        for (ClientSubscriptionModule m : activeModules) {
            if (m.getModuleName() == moduleName && "ACTIVE".equalsIgnoreCase(m.getStatus())) {
                if (m.getExpiryDate() == null || m.getExpiryDate().isAfter(java.time.LocalDateTime.now())) {
                    if (m.getOrgId() == null || m.getOrgId().equals(orgId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
