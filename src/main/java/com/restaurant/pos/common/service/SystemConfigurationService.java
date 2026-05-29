package com.restaurant.pos.common.service;

import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.entity.SystemConfiguration;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.repository.SystemConfigurationRepository;
import com.restaurant.pos.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Collections;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SystemConfigurationService {

    private final SystemConfigurationRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfigurationDto getConfiguration() {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            return mapToDto(getGlobalConfiguration());
        }
        // Branch-aware resolution: try branch config first, then client default
        UUID orgId = TenantContext.getCurrentOrg();
        SystemConfiguration config = resolveConfiguration(clientId, orgId);
        return mapToDto(config);
    }

    @Transactional
    public ConfigurationDto updateConfiguration(ConfigurationDto dto) {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) {
            throw new BusinessException("Client context is required to update system configuration");
        }

        SystemConfiguration config = getOrCreateTenantConfiguration(clientId);

        updateEntityFromDto(config, dto);
        SystemConfiguration saved = repository.save(config);
        log.info("System configuration updated successfully. clientId={}", clientId);
        return mapToDto(saved);
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
     * Save a branch-level configuration override (full copy strategy).
     * Creates a new branch config row if none exists, copying from the client default first.
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
        log.info("Branch configuration override deleted (reverted to client default). clientId={}, orgId={}", clientId, orgId);
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
                .posProductListingEnabled(true)
                .discountEnabled(true)
                .defaultBillingUiMode("standard")
                .offlineSyncEnabled(true)
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
                .productionEnabled(source.isProductionEnabled())
                .customersEnabled(source.isCustomersEnabled())
                .loyaltyEnabled(source.isLoyaltyEnabled())
                .sendToKitchenEnabled(source.isSendToKitchenEnabled())
                .onlineDeliveryEnabled(source.isOnlineDeliveryEnabled())
                .allowMultipleCustomersPerOrder(source.isAllowMultipleCustomersPerOrder())
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

    private ConfigurationDto mapToDto(SystemConfiguration entity) {
        return ConfigurationDto.builder()
                .onlinePaymentEnabled(entity.isOnlinePaymentEnabled())
                .menuImagesEnabled(entity.isMenuImagesEnabled())
                .creditEnabled(entity.isCreditEnabled())
                .creditAllocationMode(normalizeCreditAllocationMode(entity.getCreditAllocationMode()))
                .tableManagementEnabled(entity.isTableManagementEnabled())
                .qrOrderingEnabled(entity.isQrOrderingEnabled())
                .inventoryEnabled(entity.isInventoryEnabled())
                .productionEnabled(entity.isProductionEnabled())
                .customersEnabled(entity.isCustomersEnabled())
                .loyaltyEnabled(entity.isLoyaltyEnabled())
                .sendToKitchenEnabled(entity.isSendToKitchenEnabled())
                .onlineDeliveryEnabled(entity.isOnlineDeliveryEnabled())
                .allowMultipleCustomersPerOrder(entity.isAllowMultipleCustomersPerOrder())
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
                .currencySymbol(entity.getCurrencySymbol())
                .currencyPosition(entity.getCurrencyPosition())
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
        entity.setProductionEnabled(dto.isProductionEnabled());
        entity.setCustomersEnabled(dto.isCustomersEnabled());
        entity.setLoyaltyEnabled(dto.isLoyaltyEnabled());
        entity.setSendToKitchenEnabled(dto.isSendToKitchenEnabled());
        entity.setOnlineDeliveryEnabled(dto.isOnlineDeliveryEnabled());
        entity.setAllowMultipleCustomersPerOrder(dto.isAllowMultipleCustomersPerOrder());
        entity.setPosProductListingEnabled(dto.isPosProductListingEnabled());
        entity.setDiscountEnabled(dto.isDiscountEnabled());
        if (dto.getDefaultBillingUiMode() != null) entity.setDefaultBillingUiMode(dto.getDefaultBillingUiMode());
        entity.setOfflineSyncEnabled(dto.isOfflineSyncEnabled());
        entity.setOfflineSyncInterval(dto.getOfflineSyncInterval());
        entity.setOfflineLeaseBlockSize(dto.getOfflineLeaseBlockSize());
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
        if (dto.getPrintRightMarginDots() != null) entity.setPrintRightMarginDots(dto.getPrintRightMarginDots());
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
}
