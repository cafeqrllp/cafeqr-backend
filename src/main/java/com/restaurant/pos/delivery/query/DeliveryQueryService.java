package com.restaurant.pos.delivery.query;

import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.delivery.mapper.DeliveryDtoMapper;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryQueryService {

    private final ClientRepository clientRepository;
    private final OrganizationRepository organizationRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final SystemConfigurationService systemConfigurationService;
    private final DeliveryDtoMapper dtoMapper;
    private final com.restaurant.pos.purchasing.repository.CustomerRepository customerRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> getCustomerProfile(UUID clientId, String email) {
        if (clientId == null || email == null || email.isBlank()) {
            return Collections.emptyMap();
        }
        UUID effectiveClientId = clientId;
        var clientOpt = clientRepository.findById(clientId);
        if (clientOpt.isEmpty()) {
            var orgOpt = organizationRepository.findById(clientId);
            if (orgOpt.isPresent()) {
                effectiveClientId = orgOpt.get().getClientId();
            }
        }
        String normalizedEmail = email.trim().toLowerCase();
        var customerOpt = customerRepository.findByEmailAndClientId(normalizedEmail, effectiveClientId);
        if (customerOpt.isEmpty()) {
            return Collections.emptyMap();
        }
        com.restaurant.pos.purchasing.domain.Customer c = customerOpt.get();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("customerId", c.getId());
        profile.put("email", c.getEmail());
        profile.put("fullName", c.getName());
        profile.put("name", c.getName());
        profile.put("phone", c.getPhone() != null ? c.getPhone() : "");
        profile.put("address", c.getAddress() != null ? c.getAddress() : "");
        return profile;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resolveSlug(String handle, String branch) {
        String trimmedHandle = handle != null ? handle.trim() : "";
        String trimmedBranch = branch != null ? branch.trim() : null;

        Client client = null;
        Organization targetOrg = null;

        UUID possibleUuid = null;
        try {
            possibleUuid = UUID.fromString(trimmedHandle);
        } catch (Exception ignored) { }

        if (possibleUuid != null) {
            client = clientRepository.findById(possibleUuid).orElse(null);
            if (client == null) {
                var orgOpt = organizationRepository.findById(possibleUuid);
                if (orgOpt.isPresent()) {
                    targetOrg = orgOpt.get();
                    client = clientRepository.findById(targetOrg.getClientId()).orElse(null);
                }
            }
        }

        if (client == null) {
            client = clientRepository.findBySlugIgnoreCase(trimmedHandle).orElse(null);
        }

        if (client == null) {
            List<Organization> orgList = organizationRepository.findAllBySlugIgnoreCase(trimmedHandle);
            if (orgList.size() == 1) {
                targetOrg = orgList.get(0);
                client = clientRepository.findById(targetOrg.getClientId()).orElse(null);
            } else if (orgList.size() > 1) {
                log.warn("[DeliveryQueryService] Ambiguous handle '{}' matches {} orgs.", trimmedHandle, orgList.size());
            }
        }

        if (client == null) {
            throw new ResourceNotFoundException("Store not found for handle: " + trimmedHandle);
        }

        if (targetOrg == null && trimmedBranch != null && !trimmedBranch.isBlank()) {
            try {
                UUID branchUuid = UUID.fromString(trimmedBranch);
                targetOrg = organizationRepository.findByIdAndClientId(branchUuid, client.getId()).orElse(null);
            } catch (Exception ignored) { }

            if (targetOrg == null) {
                targetOrg = organizationRepository.findByClientIdAndSlugIgnoreCase(client.getId(), trimmedBranch).orElse(null);
            }

            if (targetOrg == null) {
                targetOrg = organizationRepository.findByClientIdAndBranchCodeIgnoreCase(client.getId(), trimmedBranch).orElse(null);
            }
        }

        if (targetOrg == null) {
            List<Organization> activeOrgs = organizationRepository.findByClientIdAndIsactive(client.getId(), "Y");
            if (!activeOrgs.isEmpty()) {
                targetOrg = activeOrgs.get(0);
            } else {
                List<Organization> allOrgs = organizationRepository.findAllByClientId(client.getId());
                if (!allOrgs.isEmpty()) {
                    targetOrg = allOrgs.get(0);
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clientId", client.getId());
        data.put("clientSlug", client.getSlug());
        data.put("restaurantName", client.getName());
        data.put("brandColor", nvl(client.getBrandColor(), "#f97316"));
        data.put("logoUrl", client.getLogoUrl());
        data.put("bannerUrl", client.getBannerUrl());
        data.put("posType", client.getPosType() != null ? client.getPosType() : "Restaurant");

        List<Organization> clientOrgs = organizationRepository.findByClientIdAndIsactive(client.getId(), "Y");
        if (clientOrgs.isEmpty()) {
            clientOrgs = organizationRepository.findAllByClientId(client.getId());
        }

        List<Map<String, Object>> branchesPayload = new ArrayList<>();
        for (Organization o : clientOrgs) {
            Map<String, Object> bMap = new LinkedHashMap<>();
            bMap.put("id", o.getId());
            bMap.put("orgToken", com.restaurant.pos.common.util.TokenEncryptionUtil.encryptOrgId(o.getId()));
            bMap.put("name", o.getName());
            bMap.put("slug", o.getSlug());
            bMap.put("branchCode", o.getBranchCode());
            bMap.put("posType", o.getPosType());
            bMap.put("address", o.getAddress());
            branchesPayload.add(bMap);
        }
        data.put("branches", branchesPayload);

        if (targetOrg != null) {
            data.put("orgId", targetOrg.getId());
            data.put("orgToken", com.restaurant.pos.common.util.TokenEncryptionUtil.encryptOrgId(targetOrg.getId()));
            data.put("branchSlug", targetOrg.getSlug());
            data.put("branchCode", targetOrg.getBranchCode());
            data.put("branchName", targetOrg.getName());
            if (targetOrg.getPosType() != null && !targetOrg.getPosType().isBlank()) {
                data.put("posType", targetOrg.getPosType());
            }
            if (targetOrg.getBannerUrl() != null && !targetOrg.getBannerUrl().isBlank()) {
                data.put("bannerUrl", targetOrg.getBannerUrl());
            }
            if (targetOrg.getLogoUrl() != null && !targetOrg.getLogoUrl().isBlank()) {
                data.put("logoUrl", targetOrg.getLogoUrl());
            }
            data.put("canonicalPath", "/" + client.getSlug() + "/" + targetOrg.getSlug());
        } else {
            data.put("canonicalPath", "/" + client.getSlug());
        }

        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSettings(UUID clientId, String orgIdStr) {
        UUID orgUuid = parseOrgId(orgIdStr);

        if (!isRestaurantSubscriptionActive(clientId, orgUuid)) {
            throw new BusinessException("Restaurant subscription is inactive.");
        }

        var clientOpt = clientRepository.findById(clientId);
        var client = clientOpt.orElse(null);
        if (client == null) {
            var orgOpt = organizationRepository.findById(clientId);
            if (orgOpt.isPresent()) {
                UUID actualClientId = orgOpt.get().getClientId();
                client = clientRepository.findById(actualClientId).orElse(null);
                if (orgUuid == null) {
                    orgUuid = orgOpt.get().getId();
                }
            }
        }

        if (client == null) {
            throw new ResourceNotFoundException("Restaurant not found");
        }

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("clientId",         clientId);
        settings.put("clientName",       nvl(client.getName(), "Our Restaurant"));
        settings.put("restaurantName",   nvl(client.getName(), "Our Restaurant"));
        settings.put("branchName",       "Main Branch");
        settings.put("logoUrl",          client.getLogoUrl());
        settings.put("bannerUrl",        client.getBannerUrl());
        settings.put("brandColor",       nvl(client.getBrandColor(), "#f97316"));
        settings.put("address",          client.getAddress());
        settings.put("phone",            client.getPhone());
        settings.put("whatsappNumber",   client.getWhatsappNumber());
        settings.put("currency",         nvl(client.getCurrency(), "INR"));
        settings.put("timezone",         nvl(client.getTimezone(), "Asia/Kolkata"));
        settings.put("googleMapsUrl",    client.getGoogleMapsUrl());
        settings.put("instagramUrl",     client.getInstagramUrl());
        settings.put("facebookUrl",      client.getFacebookUrl());
        settings.put("deliveryEnabled",  true);
        settings.put("takeawayEnabled",  true);
        settings.put("minOrderAmount",   BigDecimal.ZERO);
        settings.put("estimatedDeliveryMinutes", 45);

        try {
            ConfigurationDto config = systemConfigurationService.getConfigurationForClientAndBranch(clientId, orgUuid);
            settings.put("taxEnabled", config.isTaxEnabled());
            settings.put("taxLabelGlobal", config.getTaxLabelGlobal());
            settings.put("taxRates", config.getTaxRates());
            settings.put("taxDefaultId", config.getTaxDefaultId());
            settings.put("pricesIncludeTax", config.isPricesIncludeTax());
            settings.put("taxSplitEnabled", config.isTaxSplitEnabled());
            settings.put("currencyDecimalPlaces", config.getCurrencyDecimalPlaces());

            boolean onlinePayActive = config.isOnlinePaymentEnabled()
                    && config.getRazorpayKeyId() != null
                    && !config.getRazorpayKeyId().isBlank();
            settings.put("onlinePaymentEnabled", onlinePayActive);
            settings.put("razorpayKeyId", config.getRazorpayKeyId());
        } catch (Exception e) {
            log.error("Failed to load system configurations for delivery settings", e);
            settings.put("taxEnabled", false);
            settings.put("taxLabelGlobal", "GST");
            settings.put("taxRates", Collections.emptyList());
            settings.put("pricesIncludeTax", false);
            settings.put("taxSplitEnabled", true);
            settings.put("currencyDecimalPlaces", 2);
            settings.put("onlinePaymentEnabled", false);
            settings.put("razorpayKeyId", null);
        }

        if (orgUuid != null) {
            organizationRepository.findById(orgUuid).ifPresent(org -> {
                if (clientId.equals(org.getClientId())) {
                    if (org.getName() != null && !org.getName().isBlank()) {
                        settings.put("branchName", org.getName());
                    }
                    if (org.getLogoUrl() != null && !org.getLogoUrl().isBlank()) {
                        settings.put("logoUrl", org.getLogoUrl());
                    }
                    if (org.getBannerUrl() != null && !org.getBannerUrl().isBlank()) {
                        settings.put("bannerUrl", org.getBannerUrl());
                    }
                    if (org.getAddress() != null && !org.getAddress().isBlank()) {
                        settings.put("address", org.getAddress());
                    }
                    if (org.getPhone() != null && !org.getPhone().isBlank()) {
                        settings.put("phone", org.getPhone());
                    }
                    if (org.getGoogleMapsUrl() != null && !org.getGoogleMapsUrl().isBlank()) {
                        settings.put("googleMapsUrl", org.getGoogleMapsUrl());
                    }
                    if (org.getTimezone() != null && !org.getTimezone().isBlank()) {
                        settings.put("timezone", org.getTimezone());
                    }
                    if (org.getPosType() != null && !org.getPosType().isBlank()) {
                        settings.put("posType", org.getPosType());
                    }
                    settings.put("deliveryRadiusKm", org.getDeliveryRadiusKm());
                    settings.put("branchLatitude", org.getLatitude());
                    settings.put("branchLongitude", org.getLongitude());
                }
            });
        }

        if (!settings.containsKey("posType")) {
            settings.put("posType", client.getPosType() != null ? client.getPosType() : "Restaurant");
        }

        return settings;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getMenu(UUID clientId, String orgIdStr) {
        validateSubscription(clientId);

        UUID orgUuid = parseOrgId(orgIdStr);
        List<Product> products = productRepository
                .findByClientIdAndOrgIdOrGlobalAndIsActiveTrue(clientId, orgUuid);

        return products.stream()
                .filter(p -> p.isAvailable() && p.isDeliveryVisible())
                .map(p -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id",          p.getId());
                    item.put("name",        p.getName());
                    item.put("description", p.getDescription());
                    item.put("price",       p.getPrice());
                    item.put("imageUrl",    p.getImageUrl());
                    item.put("image_url",   p.getImageUrl());
                    item.put("category",    p.getCategory() != null ? p.getCategory().getName() : "Others");
                    boolean isVeg = "VEG".equalsIgnoreCase(p.getProductType())
                            || "Vegetarian".equalsIgnoreCase(p.getProductType())
                            || (!p.isPackagedGood() && p.getProductType() == null);
                    item.put("isVeg",       isVeg);
                    item.put("is_veg",      isVeg);
                    item.put("isAvailable", p.isAvailable());
                    item.put("productType", p.getProductType());
                    item.put("taxRate",     p.getTaxRate());
                    item.put("isPackagedGood", p.isPackagedGood());

                    boolean hasVariants = false;
                    try {
                        hasVariants = p.getVariantMappings() != null && !p.getVariantMappings().isEmpty();
                    } catch (Exception e) {
                        log.warn("[DeliveryQueryService] Failed checking variantMappings for product {}: {}", p.getId(), e.getMessage());
                    }
                    item.put("hasVariants",  hasVariants);
                    item.put("has_variants", hasVariants);

                    if (hasVariants) {
                        try {
                            List<Map<String, Object>> mappings = p.getVariantMappings().stream().map(m -> {
                                Map<String, Object> mMap = new LinkedHashMap<>();
                                mMap.put("id", m.getId());
                                mMap.put("isRequired", m.isRequired());
                                if (m.getVariantGroup() != null) {
                                    Map<String, Object> gMap = new LinkedHashMap<>();
                                    gMap.put("id", m.getVariantGroup().getId());
                                    gMap.put("name", m.getVariantGroup().getName());
                                    if (m.getVariantGroup().getOptions() != null) {
                                        List<Map<String, Object>> opts = m.getVariantGroup().getOptions().stream()
                                                .filter(o -> o != null && o.isActive())
                                                .map(o -> {
                                                    Map<String, Object> oMap = new LinkedHashMap<>();
                                                    oMap.put("id", o.getId());
                                                    oMap.put("name", o.getName());
                                                    oMap.put("additionalPrice", o.getAdditionalPrice());
                                                    return oMap;
                                                }).collect(Collectors.toList());
                                        gMap.put("options", opts);
                                    }
                                    mMap.put("variantGroup", gMap);
                                }
                                return mMap;
                            }).collect(Collectors.toList());
                            item.put("variantMappings", mappings);
                        } catch (Exception e) {
                            log.warn("[DeliveryQueryService] Failed mapping variantMappings for product {}: {}", p.getId(), e.getMessage());
                        }

                        try {
                            if (p.getVariantPricings() != null) {
                                List<Map<String, Object>> pricings = p.getVariantPricings().stream()
                                        .filter(pr -> pr != null && pr.isAvailable())
                                        .map(pr -> {
                                            Map<String, Object> prMap = new LinkedHashMap<>();
                                            prMap.put("id", pr.getId());
                                            prMap.put("overridePrice", pr.getOverridePrice());
                                            prMap.put("isAvailable", pr.isAvailable());
                                            if (pr.getVariantOption() != null) {
                                                Map<String, Object> voMap = new LinkedHashMap<>();
                                                voMap.put("id", pr.getVariantOption().getId());
                                                voMap.put("name", pr.getVariantOption().getName());
                                                voMap.put("additionalPrice", pr.getVariantOption().getAdditionalPrice());
                                                prMap.put("variantOption", voMap);
                                            }
                                            return prMap;
                                        }).collect(Collectors.toList());
                                item.put("variantPricings", pricings);
                            }
                        } catch (Exception e) {
                            log.warn("[DeliveryQueryService] Failed mapping variantPricings for product {}: {}", p.getId(), e.getMessage());
                        }
                    }

                    return item;
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getOrder(String orderId, UUID clientId) {
        Optional<Order> orderOpt;
        try {
            UUID uuid = UUID.fromString(orderId);
            orderOpt = orderRepository.findByIdAndClientId(uuid, clientId);
        } catch (IllegalArgumentException e) {
            orderOpt = orderRepository.findByOrderNoAndClientId(orderId, clientId);
        }

        Order order = orderOpt
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        if ("VOID".equalsIgnoreCase(order.getOrderStatus()) || "N".equalsIgnoreCase(order.getIsactive())) {
            String baseOrderNo = order.getOrderNo();
            if (baseOrderNo != null && baseOrderNo.contains("_VOID_")) {
                baseOrderNo = baseOrderNo.substring(0, baseOrderNo.indexOf("_VOID_"));
            }
            Optional<Order> activeOrder = orderRepository.findActiveByOrderNoAndClientId(baseOrderNo, clientId);
            if (activeOrder.isPresent()) {
                order = activeOrder.get();
            }
        }

        return dtoMapper.toResponseMap(order);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOrders(UUID clientId, String email) {
        String cleanEmail = email != null ? email.trim().toLowerCase() : "";
        List<Order> orders = orderRepository.findByClientIdAndOrderStatusInOrderByCreatedAtDesc(
                        clientId,
                        List.of("PENDING", "PLACED", "CONFIRMED", "PREPARING", "OUT_FOR_DELIVERY", "DELIVERED", "BILLED", "PAID", "COMPLETED", "CANCELLED"))
                .stream()
                .filter(o -> "DELIVERY_WEB".equalsIgnoreCase(o.getOrderSource()) || "DELIVERY".equalsIgnoreCase(o.getFulfillmentType()))
                .filter(o -> {
                    if (cleanEmail.isBlank()) return true;
                    String desc = o.getDescription() != null ? o.getDescription().toLowerCase() : "";
                    return desc.contains(cleanEmail);
                })
                .sorted(Comparator.comparing(Order::getOrderDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        return orders.stream()
                .map(dtoMapper::toResponseMap)
                .collect(Collectors.toList());
    }

    private void validateSubscription(UUID clientId) {
        if (clientId == null) {
            throw new BusinessException("Client ID is required");
        }
        var clientOpt = clientRepository.findById(clientId);
        if (clientOpt.isPresent()) {
            Client client = clientOpt.get();
            if (!"ACTIVE".equalsIgnoreCase(client.getSubscriptionStatus()) && !"TRIAL".equalsIgnoreCase(client.getSubscriptionStatus())) {
                throw new BusinessException("Restaurant subscription is inactive.");
            }
        }
    }

    private boolean isRestaurantSubscriptionActive(UUID clientId, UUID orgUuid) {
        if (clientId == null) return false;
        var clientOpt = clientRepository.findById(clientId);
        if (clientOpt.isPresent()) {
            Client client = clientOpt.get();
            return "ACTIVE".equalsIgnoreCase(client.getSubscriptionStatus()) || "TRIAL".equalsIgnoreCase(client.getSubscriptionStatus());
        }
        if (orgUuid != null) {
            var orgOpt = organizationRepository.findById(orgUuid);
            if (orgOpt.isPresent()) {
                var parentClient = clientRepository.findById(orgOpt.get().getClientId());
                if (parentClient.isPresent()) {
                    Client c = parentClient.get();
                    return "ACTIVE".equalsIgnoreCase(c.getSubscriptionStatus()) || "TRIAL".equalsIgnoreCase(c.getSubscriptionStatus());
                }
            }
        }
        return false;
    }

    private UUID parseOrgId(String orgIdStr) {
        if (orgIdStr == null || orgIdStr.isBlank() || "null".equalsIgnoreCase(orgIdStr)) {
            return null;
        }
        return com.restaurant.pos.common.util.TokenEncryptionUtil.decryptOrgId(orgIdStr);
    }

    private String nvl(String val, String fallback) {
        return (val != null && !val.isBlank()) ? val : fallback;
    }
}
