package com.restaurant.pos.delivery.controller;

import com.restaurant.pos.client.repository.ClientRepository;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.domain.TaxType;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.print.service.PrintJobService;
import com.restaurant.pos.print.domain.PrintJobKind;
import com.restaurant.pos.push.service.PushNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Public (unauthenticated) API for the CafeQR Delivery Website.
 *
 * All endpoints are scoped to a clientId so a single backend instance
 * can serve multiple restaurants. No staff/POS auth is required —
 * customers interact directly with these endpoints.
 *
 * Base path: /delivery
 *
 * Endpoints:
 *   GET  /delivery/restaurant/{clientId}/settings        — brand + restaurant info
 *   GET  /delivery/restaurant/{clientId}/menu            — active menu items
 *   POST /delivery/orders                                — place a delivery/takeaway order
 *   GET  /delivery/orders/{orderId}                      — track a single order
 *   GET  /delivery/orders?clientId=&email=               — list orders for a customer email
 *   POST /delivery/fcm-tokens                            — register FCM push token (stub)
 *   GET  /delivery/addresses?clientId=&email=            — list saved addresses (stub)
 *   POST /delivery/addresses                             — save a delivery address (stub)
 */
@Slf4j
@RestController
@RequestMapping("/api/delivery")
@RequiredArgsConstructor
public class DeliveryController {

    private final ClientRepository       clientRepository;
    private final OrganizationRepository organizationRepository;
    private final ProductRepository      productRepository;
    private final OrderRepository        orderRepository;
    private final SystemConfigurationService systemConfigurationService;
    private final PrintJobService        printJobService;
    private final PushNotificationService pushNotificationService;

    // ─────────────────────────────────────────────────────────────────────────
    // 1. GET /delivery/restaurant/{clientId}/settings
    // Returns brand colour, name, logo, contact info, delivery toggle.
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/restaurant/{clientId}/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSettings(
            @PathVariable UUID clientId,
            @RequestParam(required = false) String orgId) {

        var client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        if (!client.isSubscriptionActive()) {
            throw new BusinessException("Restaurant subscription is inactive.");
        }

        UUID orgUuid = parseOrgId(orgId);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("clientId",         clientId);
        settings.put("restaurantName",   nvl(client.getName(), "Our Restaurant"));
        settings.put("logoUrl",          client.getLogoUrl());
        settings.put("brandColor",       nvl(client.getBrandColor(), "#f97316"));
        settings.put("address",          client.getAddress());
        settings.put("phone",            client.getPhone());
        settings.put("whatsappNumber",   client.getWhatsappNumber());
        settings.put("currency",         nvl(client.getCurrency(), "INR"));
        settings.put("timezone",         nvl(client.getTimezone(), "Asia/Kolkata"));
        settings.put("googleMapsUrl",    client.getGoogleMapsUrl());
        settings.put("instagramUrl",     client.getInstagramUrl());
        settings.put("facebookUrl",      client.getFacebookUrl());
        settings.put("deliveryEnabled",  true);   // Always true on this endpoint
        settings.put("takeawayEnabled",  true);
        settings.put("minOrderAmount",   BigDecimal.ZERO);
        settings.put("estimatedDeliveryMinutes", 45);

        // Load configurations
        try {
            ConfigurationDto config = systemConfigurationService.getConfigurationForClientAndBranch(clientId, orgUuid);
            settings.put("taxEnabled", config.isTaxEnabled());
            settings.put("taxLabelGlobal", config.getTaxLabelGlobal());
            settings.put("taxRates", config.getTaxRates());
            settings.put("taxDefaultId", config.getTaxDefaultId());
            settings.put("pricesIncludeTax", config.isPricesIncludeTax());
            settings.put("taxSplitEnabled", config.isTaxSplitEnabled());
            settings.put("currencyDecimalPlaces", config.getCurrencyDecimalPlaces());
        } catch (Exception e) {
            log.error("Failed to load system configurations for delivery settings", e);
            settings.put("taxEnabled", false);
            settings.put("taxLabelGlobal", "GST");
            settings.put("taxRates", Collections.emptyList());
            settings.put("pricesIncludeTax", false);
            settings.put("taxSplitEnabled", true);
            settings.put("currencyDecimalPlaces", 2);
        }

        // Branch-wise settings override
        if (orgUuid != null) {
            organizationRepository.findById(orgUuid).ifPresent(org -> {
                if (clientId.equals(org.getClientId())) {
                    if (org.getName() != null && !org.getName().isBlank()) {
                        settings.put("restaurantName", org.getName());
                    }
                    if (org.getLogoUrl() != null && !org.getLogoUrl().isBlank()) {
                        settings.put("logoUrl", org.getLogoUrl());
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
                    // Delivery radius and branch coordinates for delivery zone validation
                    settings.put("deliveryRadiusKm", org.getDeliveryRadiusKm());
                    settings.put("branchLatitude", org.getLatitude());
                    settings.put("branchLongitude", org.getLongitude());
                }
            });
        }

        return ResponseEntity.ok(ApiResponse.success(settings));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. GET /delivery/restaurant/{clientId}/menu
    // Returns active, available products scoped to clientId.
    // Optional ?orgId= to narrow to a specific branch.
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/restaurant/{clientId}/menu")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMenu(
            @PathVariable UUID clientId,
            @RequestParam(required = false) String orgId) {

        validateSubscription(clientId);

        UUID orgUuid = parseOrgId(orgId);
        List<Product> products = productRepository
                .findByClientIdAndOrgIdOrGlobalAndIsActiveTrue(clientId, orgUuid);

        List<Map<String, Object>> menu = products.stream()
                .filter(Product::isAvailable)
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
                    return item;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(menu));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. POST /delivery/orders
    // Places a new delivery or takeaway order.
    //
    // Expected request body:
    // {
    //   "clientId":        "uuid",
    //   "orgId":           "uuid" | null,
    //   "customerEmail":   "string",
    //   "customerName":    "string",
    //   "customerPhone":   "string",
    //   "fulfillmentType": "DELIVERY" | "TAKEAWAY",
    //   "deliveryAddress": "string",
    //   "note":            "string",
    //   "items": [
    //     { "productId": "uuid", "quantity": 2 }
    //   ]
    // }
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional
    @PostMapping("/orders")
    public ResponseEntity<ApiResponse<Map<String, Object>>> placeOrder(
            @RequestBody Map<String, Object> payload) {

        try {
            UUID clientId = UUID.fromString((String) payload.get("clientId"));
            validateSubscription(clientId);

            UUID orgUuid          = parseOrgId((String) payload.get("orgId"));
            String fulfillment    = String.valueOf(payload.getOrDefault("fulfillmentType", "DELIVERY")).toUpperCase();
            String customerEmail  = (String) payload.getOrDefault("customerEmail", "");
            String customerName   = (String) payload.getOrDefault("customerName",  "");
            String customerPhone  = (String) payload.getOrDefault("customerPhone", "");
            String deliveryAddress= (String) payload.getOrDefault("deliveryAddress", "");
            String note           = (String) payload.getOrDefault("note", "");
            String remarks        = (String) payload.getOrDefault("remarks", "");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");

            if (items == null || items.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.success(Map.of("error", "No items in order")));
            }

            String orderNo = "DEL-" + System.currentTimeMillis();
            String description = buildDescription(customerEmail, customerName, customerPhone, deliveryAddress, note);

            BigDecimal latitude = null;
            BigDecimal longitude = null;
            if (payload.get("latitude") != null) {
                try {
                    latitude = new BigDecimal(String.valueOf(payload.get("latitude")));
                } catch (Exception e) {
                    // Ignore parsing errors
                }
            }
            if (payload.get("longitude") != null) {
                try {
                    longitude = new BigDecimal(String.valueOf(payload.get("longitude")));
                } catch (Exception e) {
                    // Ignore parsing errors
                }
            }

            // ── Delivery radius validation ──────────────────────────────────
            if ("DELIVERY".equalsIgnoreCase(fulfillment) && orgUuid != null && latitude != null && longitude != null) {
                final double customerLat = latitude.doubleValue();
                final double customerLng = longitude.doubleValue();
                organizationRepository.findById(orgUuid).ifPresent(org -> {
                    if (org.getDeliveryRadiusKm() != null && org.getDeliveryRadiusKm() > 0
                            && org.getLatitude() != null && org.getLongitude() != null) {
                        double distKm = haversineDistanceKm(
                                org.getLatitude(), org.getLongitude(),
                                customerLat, customerLng);
                        if (distKm > org.getDeliveryRadiusKm()) {
                            throw new BusinessException(String.format(
                                    "Your location is %.1f km away. Delivery is only available within %.0f km of the restaurant.",
                                    distKm, org.getDeliveryRadiusKm()));
                        }
                    }
                });
            }

            // FIX 1: clientId and orgId live in BaseEntity and are not reachable via
            // the Lombok @Builder generated on Order itself. Build without them, then
            // set via the inherited Lombok setters.
            Order order = Order.builder()
                    .id(UUID.randomUUID())
                    .orderNo(orderNo)
                    .orderType(OrderType.SALE)
                    .orderStatus("PENDING")
                    .paymentStatus("PENDING")
                    .orderSource("DELIVERY_WEB")
                    .fulfillmentType(fulfillment)
                    .description(description)
                    .remarks(remarks)
                    .orderDate(Instant.now())
                    .isactive("Y")
                    .build();

            order.setClientId(clientId);
            order.setOrgId(orgUuid);
            order.setLatitude(latitude);
            order.setLongitude(longitude);

            // Load configuration for client & orgId
            ConfigurationDto config = null;
            try {
                config = systemConfigurationService.getConfigurationForClientAndBranch(clientId, orgUuid);
            } catch (Exception e) {
                log.warn("[Delivery] Failed to fetch system configuration, using fallback defaults", e);
            }

            boolean gstEnabled = config != null && config.isTaxEnabled();
            boolean pricesIncludeTax = config != null && config.isPricesIncludeTax();
            int decimalPlaces = (config != null && config.getCurrencyDecimalPlaces() != null) ? config.getCurrencyDecimalPlaces() : 2;

            // Resolve default base rate
            BigDecimal baseRate = BigDecimal.ZERO;
            List<Map<String, Object>> taxRatesList = new ArrayList<>();
            String defaultTaxId = config != null ? config.getTaxDefaultId() : null;

            if (config != null && config.getTaxRates() != null) {
                for (Object rateObj : config.getTaxRates()) {
                    if (rateObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> rateMap = (Map<String, Object>) rateObj;
                        taxRatesList.add(rateMap);
                    }
                }
            }

            if (gstEnabled && !taxRatesList.isEmpty()) {
                // Find default tax rate
                Map<String, Object> defaultRateMap = null;
                if (defaultTaxId != null) {
                    defaultRateMap = taxRatesList.stream()
                            .filter(r -> defaultTaxId.equals(String.valueOf(r.get("id"))))
                            .findFirst().orElse(null);
                }
                if (defaultRateMap == null) {
                    defaultRateMap = taxRatesList.get(0);
                }
                if (defaultRateMap != null && defaultRateMap.get("value") != null) {
                    try {
                        baseRate = new BigDecimal(String.valueOf(defaultRateMap.get("value")));
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

            BigDecimal totalTaxableAmount = BigDecimal.ZERO;
            BigDecimal totalTaxAmount = BigDecimal.ZERO;
            BigDecimal totalGrossAmount = BigDecimal.ZERO;
            BigDecimal grandTotal = BigDecimal.ZERO;

            for (Map<String, Object> cartItem : items) {
                UUID productId = UUID.fromString((String) cartItem.get("productId"));
                int qty = ((Number) cartItem.get("quantity")).intValue();

                Optional<Product> productOpt = productRepository.findWithCategoryById(productId)
                        .filter(p -> clientId.equals(p.getClientId()))
                        .filter(p -> orgUuid == null || p.getOrgId() == null || orgUuid.equals(p.getOrgId()))
                        .filter(Product::isActive)
                        .filter(Product::isAvailable);

                if (productOpt.isEmpty()) {
                    log.warn("[Delivery] Invalid/unavailable product {} for client {} org {}",
                            productId, clientId, orgUuid);
                    return ResponseEntity.badRequest()
                            .body(ApiResponse.success(Map.of("error", "Invalid or unavailable item: " + productId)));
                }

                Product p = productOpt.get();
                BigDecimal faceUnit = p.getPrice();
                BigDecimal quantity = BigDecimal.valueOf(qty);
                BigDecimal grossLineAmount = faceUnit.multiply(quantity);

                // Determine line tax rate
                boolean isPackaged = p.isPackagedGood();
                BigDecimal rate = BigDecimal.ZERO;
                if (gstEnabled) {
                    if (isPackaged) {
                        rate = p.getTaxRate() != null ? p.getTaxRate() : baseRate;
                    } else {
                        rate = baseRate;
                    }
                }

                boolean isInclusive = gstEnabled && (isPackaged || pricesIncludeTax);

                // Determine unitPriceExTax and base line totals
                BigDecimal baseUnit;
                BigDecimal lineTotal;
                BigDecimal taxable;
                BigDecimal tax;

                if (isInclusive && rate.compareTo(BigDecimal.ZERO) > 0) {
                    baseUnit = faceUnit.divide(BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 10, RoundingMode.HALF_UP);
                    lineTotal = grossLineAmount.setScale(decimalPlaces, RoundingMode.HALF_UP);
                    taxable = lineTotal.divide(BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), decimalPlaces, RoundingMode.HALF_UP);
                    tax = lineTotal.subtract(taxable);
                } else {
                    baseUnit = faceUnit;
                    taxable = grossLineAmount.setScale(decimalPlaces, RoundingMode.HALF_UP);
                    tax = taxable.multiply(rate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)).setScale(decimalPlaces, RoundingMode.HALF_UP);
                    lineTotal = taxable.add(tax);
                }

                // Find tax code and name snapshots
                String taxCode = null;
                String taxName = null;
                if (gstEnabled && rate.compareTo(BigDecimal.ZERO) > 0) {
                    final BigDecimal finalRate = rate;
                    Map<String, Object> matchedRate = taxRatesList.stream()
                            .filter(r -> {
                                try {
                                    return new BigDecimal(String.valueOf(r.get("value"))).compareTo(finalRate) == 0;
                                } catch (Exception e) {
                                    return false;
                                }
                            })
                            .findFirst().orElse(null);

                    if (matchedRate != null) {
                        taxCode = (String) matchedRate.get("code");
                        taxName = (String) matchedRate.get("name");
                    }
                    if (taxCode == null) {
                        taxCode = "GST_" + rate.toPlainString();
                    }
                    if (taxName == null) {
                        taxName = "GST " + rate.toPlainString() + "%";
                    }
                }

                TaxType taxType = isInclusive ? TaxType.INCLUSIVE : (gstEnabled && rate.compareTo(BigDecimal.ZERO) > 0 ? TaxType.EXCLUSIVE : TaxType.NONE);

                OrderLine line = OrderLine.builder()
                        .productId(productId)
                        .productName(p.getName())
                        .categoryName(p.getCategory() != null ? p.getCategory().getName() : null)
                        .isPackagedGood(isPackaged)
                        .quantity(quantity)
                        .unitOfMeasure(p.getUom() != null ? p.getUom().getName() : "units")
                        .unitPrice(faceUnit)
                        .taxRate(rate)
                        .taxAmount(tax)
                        .discountAmount(BigDecimal.ZERO)
                        .lineTotal(lineTotal)
                        .grossLineAmount(grossLineAmount)
                        .unitPriceExTax(baseUnit.setScale(4, RoundingMode.HALF_UP))
                        .taxableAmount(taxable)
                        .taxType(taxType)
                        .taxSnapshotRate(rate)
                        .taxCode(taxCode)
                        .taxName(taxName)
                        .allocatedOrderDiscount(BigDecimal.ZERO)
                        .isactive("Y")
                        .build();

                order.addLine(line);

                totalTaxableAmount = totalTaxableAmount.add(taxable);
                totalTaxAmount = totalTaxAmount.add(tax);
                totalGrossAmount = totalGrossAmount.add(grossLineAmount);
                grandTotal = grandTotal.add(lineTotal);
            }

            order.setGrossAmount(totalGrossAmount);
            order.setTotalTaxAmount(totalTaxAmount);
            order.setTotalDiscountAmount(BigDecimal.ZERO);
            order.setTotalAmount(grandTotal);
            order.setGrandTotal(grandTotal);

            Order saved = orderRepository.save(order);
            log.info("[Delivery] Order placed: {} (orderNo={}) for client={} org={}",
                    saved.getId(), saved.getOrderNo(), clientId, orgUuid);

            try {
                pushNotificationService.sendNewOrderPush(saved);
            } catch (Exception ex) {
                log.error("[Delivery] Failed to trigger push notification", ex);
            }

            if (isKitchenPrintStatus(saved.getOrderStatus())) {
                try {
                    printJobService.enqueueForOrder(saved, PrintJobKind.KOT, "auto");
                } catch (Exception ex) {
                    log.warn("[Delivery] Unable to enqueue print job for order {}", saved.getId(), ex);
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("orderId",         saved.getId());
            response.put("orderNo",         saved.getOrderNo());
            response.put("status",          saved.getOrderStatus());
            response.put("paymentStatus",   saved.getPaymentStatus());
            response.put("fulfillmentType", saved.getFulfillmentType());
            response.put("grandTotal",      saved.getGrandTotal());

            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception ex) {
            log.error("[Delivery] Failed to place order: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. GET /delivery/orders/{orderId}
    // Returns full order details for order tracking.
    // ?clientId= required for tenant scoping.
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOrder(
            @PathVariable String orderId,
            @RequestParam UUID clientId) {

        // Try UUID lookup first, then fall back to orderNo lookup
        Optional<Order> orderOpt;
        try {
            UUID uuid = UUID.fromString(orderId);
            orderOpt = orderRepository.findByIdAndClientId(uuid, clientId);
        } catch (IllegalArgumentException e) {
            // Not a UUID — treat as orderNo
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

        return ResponseEntity.ok(ApiResponse.success(toOrderMap(order)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. GET /delivery/orders
    // Lists orders for a customer by email + clientId.
    // ?clientId= &email= (both required)
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listOrders(
            @RequestParam UUID clientId,
            @RequestParam String email) {

        // FIX 2: findByClientIdAndOrderStatusIn(UUID, List) does not exist in
        // OrderRepository — the no-Pageable variant is named
        // findByClientIdAndOrderStatusInOrderByCreatedAtDesc.
        List<Order> orders = orderRepository.findByClientIdAndOrderStatusInOrderByCreatedAtDesc(
                        clientId,
                        List.of("CONFIRMED", "PREPARING", "OUT_FOR_DELIVERY", "DELIVERED", "COMPLETED", "CANCELLED"))
                .stream()
                .filter(o -> "DELIVERY_WEB".equals(o.getOrderSource()))
                .filter(o -> o.getDescription() != null && o.getDescription().contains(email))
                .sorted(Comparator.comparing(Order::getOrderDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        List<Map<String, Object>> result = orders.stream()
                .map(this::toOrderMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. POST /delivery/fcm-tokens
    // Registers a customer FCM push token for order status notifications.
    // Stored as a no-op stub — full FCM fan-out requires a notifications table.
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/fcm-tokens")
    public ResponseEntity<ApiResponse<Map<String, Object>>> registerFcmToken(
            @RequestBody Map<String, Object> payload) {
        // Stub — accept and acknowledge the token, fire-and-forget
        // Full implementation: persist to a customer_fcm_tokens table and
        // fan-out via Firebase Admin SDK when order status changes.
        return ResponseEntity.ok(ApiResponse.success(Map.of("registered", true)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. GET /delivery/addresses
    // Returns saved delivery addresses for a customer.
    // Stub — returns empty list until a customer_addresses table is added.
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/addresses")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAddresses(
            @RequestParam UUID clientId,
            @RequestParam String email) {
        // Stub — full implementation requires a customer_addresses table
        return ResponseEntity.ok(ApiResponse.success(Collections.emptyList()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. POST /delivery/addresses
    // Saves a new delivery address for a customer.
    // Stub — accepts and acknowledges until persistence layer is ready.
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/addresses")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveAddress(
            @RequestBody Map<String, Object> payload) {
        // Stub — full implementation requires a customer_addresses table
        return ResponseEntity.ok(ApiResponse.success(Map.of("saved", true)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void validateSubscription(UUID clientId) {
        var client = clientRepository.findById(clientId).orElse(null);
        if (client == null || !client.isSubscriptionActive()) {
            throw new BusinessException("Restaurant subscription is inactive or not found.");
        }
    }

    private UUID parseOrgId(String orgId) {
        if (orgId == null || orgId.isBlank() || "null".equalsIgnoreCase(orgId)) return null;
        try { return UUID.fromString(orgId); } catch (IllegalArgumentException e) { return null; }
    }

    private String nvl(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private boolean isKitchenPrintStatus(String status) {
        return "KITCHEN".equalsIgnoreCase(status)
                || "CONFIRMED".equalsIgnoreCase(status)
                || "IN_PROGRESS".equalsIgnoreCase(status)
                || "READY".equalsIgnoreCase(status);
    }

    private String buildDescription(String email, String name, String phone, String address, String note) {
        StringBuilder sb = new StringBuilder();
        if (email   != null && !email.isBlank())   sb.append("email:").append(email).append(" ");
        if (name    != null && !name.isBlank())    sb.append("name:").append(name).append(" ");
        if (phone   != null && !phone.isBlank())   sb.append("phone:").append(phone).append(" ");
        if (address != null && !address.isBlank()) sb.append("address:").append(address).append(" ");
        if (note    != null && !note.isBlank())    sb.append("note:").append(note);
        return sb.toString().trim();
    }

    private Map<String, Object> toOrderMap(Order order) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("orderId",         order.getId());
        map.put("orderNo",         order.getOrderNo());
        map.put("status",          order.getOrderStatus());
        map.put("paymentStatus",   order.getPaymentStatus());
        map.put("fulfillmentType", order.getFulfillmentType());
        map.put("grandTotal",      order.getGrandTotal());
        map.put("orderDate",       order.getOrderDate());
        map.put("description",     order.getDescription());
        map.put("remarks",         order.getRemarks());
        map.put("latitude",        order.getLatitude());
        map.put("longitude",       order.getLongitude());

        Double shopLat = null;
        Double shopLng = null;
        if (order.getOrgId() != null) {
            var orgOpt = organizationRepository.findById(order.getOrgId());
            if (orgOpt.isPresent()) {
                shopLat = orgOpt.get().getLatitude();
                shopLng = orgOpt.get().getLongitude();
            }
        }
        map.put("shopLatitude",    shopLat);
        map.put("shopLongitude",   shopLng);

        if (order.getLines() != null) {
            List<Map<String, Object>> lines = order.getLines().stream().map(l -> {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("productId",   l.getProductId());
                lm.put("productName", l.getProductName());
                lm.put("quantity",    l.getQuantity());
                lm.put("unitPrice",   l.getUnitPrice());
                lm.put("lineTotal",   l.getLineTotal());
                return lm;
            }).collect(Collectors.toList());
            map.put("items", lines);
        }

        return map;
    }

    /**
     * Haversine formula — computes the great-circle distance in km between two
     * lat/lng points on Earth.
     */
    private double haversineDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0; // Earth's mean radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
