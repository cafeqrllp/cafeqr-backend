package com.restaurant.pos.qrmenu.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.purchasing.domain.Customer;
import com.restaurant.pos.purchasing.repository.CustomerRepository;
import com.restaurant.pos.table.domain.RestaurantTable;
import com.restaurant.pos.table.repository.RestaurantTableRepository;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.print.domain.PrintJobKind;
import com.restaurant.pos.print.service.PrintJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Public (unauthenticated) API for the QR-code customer ordering flow.
 * Customers scan a QR code containing clientId/orgId/tableId, which opens
 * the digital menu. They can browse products and place orders — all without
 * needing to log in.
 */
@RestController
@RequestMapping("/api/v1/public/menu")
@RequiredArgsConstructor
public class PublicMenuController {

    private final ProductRepository productRepository;
    private final RestaurantTableRepository tableRepository;
    private final OrderRepository orderRepository;
    private final com.restaurant.pos.client.repository.ClientRepository clientRepository;
    private final com.restaurant.pos.client.repository.OrganizationRepository organizationRepository;
    private final SystemConfigurationService systemConfigurationService;
    private final CustomerRepository customerRepository;
    private final PrintJobService printJobService;

    /**
     * GET /api/v1/public/menu/{clientId}/{orgId}
     * Returns the full active menu for a given restaurant (client + org).
     */
    @GetMapping("/{clientId}/{orgId}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMenu(
            @PathVariable UUID clientId,
            @PathVariable String orgId) {
        UUID orgUuid = (orgId == null || "null".equals(orgId)) ? null : UUID.fromString(orgId);
        validateSubscription(clientId, orgUuid);
        List<Product> products = productRepository
                .findByClientIdAndOrgIdOrGlobalAndIsActiveTrue(clientId, orgUuid);

        // Filter to only available products and map to a lightweight DTO
        List<Map<String, Object>> menu = products.stream()
                .filter(Product::isAvailable)
                .map(p -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", p.getId());
                    item.put("name", p.getName());
                    item.put("description", p.getDescription());
                    item.put("price", p.getPrice());
                    item.put("imageUrl", p.getImageUrl());
                    item.put("category", p.getCategory() != null ? p.getCategory().getName() : "Others");
                    item.put("isVeg", !p.isPackagedGood()); // Use productType or a dedicated field
                    item.put("isAvailable", p.isAvailable());
                    item.put("productType", p.getProductType());
                    return item;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(menu));
    }

    /**
     * GET /api/v1/public/menu/{clientId}/{orgId}/table/{tableId}
     * Returns table info for the scanned QR code.
     */
    @GetMapping("/{clientId}/{orgId}/table/{tableId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTableInfo(
            @PathVariable UUID clientId,
            @PathVariable String orgId,
            @PathVariable UUID tableId) {
        UUID orgUuid = (orgId == null || "null".equals(orgId)) ? null : UUID.fromString(orgId);
        validateSubscription(clientId, orgUuid);

        Optional<RestaurantTable> tableOpt = tableRepository.findById(tableId);

        if (tableOpt.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "found", false,
                    "message", "Table not found"
            )));
        }

        RestaurantTable table = tableOpt.get();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("found", true);
        info.put("id", table.getId());
        info.put("tableNumber", table.getTableNumber());
        info.put("name", table.getName());
        info.put("status", table.getStatus());
        info.put("seatingCapacity", table.getSeatingCapacity());
        info.put("floor", table.getFloor());
        info.put("section", table.getSection());

        // Attach brand color, name, and logo (Prefer Organization-specific, fallback to Client-global)
        UUID effectiveClientId = table.getClientId() != null ? table.getClientId() : clientId;
        UUID effectiveOrgId = table.getOrgId() != null ? table.getOrgId() : orgUuid;
        info.put("onlinePaymentEnabled", systemConfigurationService.getConfigurationForClientAndBranch(effectiveClientId, effectiveOrgId).isOnlinePaymentEnabled());

        clientRepository.findById(effectiveClientId).ifPresent(client -> {
            info.put("brandColor", client.getBrandColor() != null ? client.getBrandColor() : "#f97316");
            info.put("restaurantName", client.getName() != null ? client.getName() : "Our Restaurant");
            info.put("logoUrl", client.getLogoUrl());
        });

        // Override with Organization info if available
        if (effectiveOrgId != null) {
            organizationRepository.findById(effectiveOrgId).ifPresent(org -> {
                if (org.getLogoUrl() != null && !org.getLogoUrl().isBlank()) {
                    info.put("logoUrl", org.getLogoUrl());
                }
                if (org.getName() != null && !org.getName().isBlank()) {
                    info.put("restaurantName", org.getName());
                }
            });
        }

        return ResponseEntity.ok(ApiResponse.success(info));
    }

    /**
     * GET /api/v1/public/menu/{clientId}/default-org
     * Helper endpoint for legacy QR code redirects. Returns the default orgId for a given clientId.
     */
    @GetMapping("/{clientId}/default-org")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDefaultOrg(@PathVariable UUID clientId) {
        List<com.restaurant.pos.client.domain.Organization> orgs = organizationRepository.findAllByClientId(clientId);
        
        if (orgs == null || orgs.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(Map.of("orgId", clientId))); // Fallback
        }
        
        // Find HQ or just use the first one
        com.restaurant.pos.client.domain.Organization defaultOrg = orgs.stream()
                .filter(o -> "HQ".equals(o.getBranchCode()))
                .findFirst()
                .orElse(orgs.get(0));
                
        return ResponseEntity.ok(ApiResponse.success(Map.of("orgId", defaultOrg.getId())));
    }

    /**
     * POST /api/v1/public/menu/{clientId}/{orgId}/order
     * Places an order from the QR menu (no auth required).
     */
    @PostMapping("/{clientId}/{orgId}/order")
    public ResponseEntity<ApiResponse<Map<String, Object>>> placeOrder(
            @PathVariable UUID clientId,
            @PathVariable String orgId,
            @RequestBody Map<String, Object> payload) {
        UUID orgUuid = (orgId == null || "null".equals(orgId)) ? null : UUID.fromString(orgId);
        validateSubscription(clientId, orgUuid);

        String tableNumber = (String) payload.getOrDefault("tableNumber", "QR");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
        String customerNote = (String) payload.getOrDefault("note", "");
        String paymentStatus = String.valueOf(payload.getOrDefault("paymentStatus", "PENDING")).toUpperCase();
        String paymentMethod = String.valueOf(payload.getOrDefault("paymentMethod", "CASH")).toUpperCase();
        String razorpayPaymentId = (String) payload.getOrDefault("razorpayPaymentId", null);
        String razorpayOrderId = (String) payload.getOrDefault("razorpayOrderId", null);

        if (items == null || items.isEmpty()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "No items in order");
            return ResponseEntity.badRequest()
                    .body(ApiResponse.success(error));
        }

        // Generate order number
        String orderNo = "QR-" + System.currentTimeMillis();

        // Build the order
        Order order = Order.builder()
                .id(UUID.randomUUID())
                .orderNo(orderNo)
                .orderType(OrderType.SALE)   // QR menu orders are always customer sales
                .orderStatus("CONFIRMED")
                .paymentStatus("PAID".equals(paymentStatus) ? "PAID" : "PENDING")
                .orderSource("QR_MENU")
                .fulfillmentType("DINE_IN")
                .tableNumber(tableNumber)
                .description(customerNote)
                .reference(buildPaymentReference(paymentMethod, razorpayPaymentId, razorpayOrderId))
                .orderDate(Instant.now())
                .isactive("Y")
                .build();
                
        String tableIdStr = (String) payload.get("tableId");
        if (tableIdStr != null && !tableIdStr.isBlank()) {
            order.setTableId(UUID.fromString(tableIdStr));
        }
                
        String customerIdStr = (String) payload.get("customerId");
        if (customerIdStr != null && !customerIdStr.isBlank()) {
            order.setCustomerId(UUID.fromString(customerIdStr));
        }
        
        order.setClientId(clientId);
        order.setOrgId(orgUuid);

        BigDecimal grandTotal = BigDecimal.ZERO;

        for (Map<String, Object> cartItem : items) {
            UUID productId = UUID.fromString((String) cartItem.get("productId"));
            int qty = ((Number) cartItem.get("quantity")).intValue();
            Optional<Product> productOpt = productRepository.findWithCategoryById(productId)
                    .filter(product -> clientId.equals(product.getClientId()))
                    .filter(product -> orgUuid == null || product.getOrgId() == null || orgUuid.equals(product.getOrgId()))
                    .filter(Product::isActive)
                    .filter(Product::isAvailable);

            if (productOpt.isEmpty()) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "Invalid menu item in order");
                return ResponseEntity.badRequest()
                        .body(ApiResponse.success(error));
            }

            Product product = productOpt.get();
            BigDecimal price = product.getPrice();
            BigDecimal lineTotal = price.multiply(BigDecimal.valueOf(qty));
            String productName = product.getName() != null ? product.getName() : (String) cartItem.getOrDefault("name", null);
            String categoryName = product.getCategory() != null
                    ? product.getCategory().getName()
                    : (String) cartItem.getOrDefault("category", null);

            OrderLine line = OrderLine.builder()
                    .productId(productId)
                    .productName(productName)
                    .categoryName(categoryName)
                    .isPackagedGood(product.isPackagedGood())
                    .quantity(BigDecimal.valueOf(qty))
                    .unitPrice(price)
                    .lineTotal(lineTotal)
                    .isactive("Y")
                    .build();
            order.addLine(line);

            grandTotal = grandTotal.add(lineTotal);
        }

        order.setTotalAmount(grandTotal);
        order.setGrandTotal(grandTotal);

        Order saved = orderRepository.save(order);
        linkCustomerToOrder(saved);

        try {
            printJobService.enqueueForOrder(saved, PrintJobKind.KOT, "auto");
        } catch (Exception ex) {
            // Silence print queue failures to avoid blocking guest order placement
        }

        // Update table status to OCCUPIED
        if (tableIdStr != null && !tableIdStr.isBlank()) {
            try {
                UUID tid = UUID.fromString(tableIdStr);
                tableRepository.findByIdAndClientIdAndOrgId(tid, clientId, orgUuid).ifPresent(tbl -> {
                    if (!"OCCUPIED".equals(tbl.getStatus())) {
                        tbl.setStatus("OCCUPIED");
                        tableRepository.save(tbl);
                    }
                });
            } catch (Exception e) {
                // Ignore invalid UUID or other errors
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderId", saved.getId());
        response.put("orderNo", saved.getOrderNo());
        response.put("status", saved.getOrderStatus());
        response.put("paymentStatus", saved.getPaymentStatus());
        response.put("grandTotal", saved.getGrandTotal());
        response.put("tableNumber", saved.getTableNumber());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private String buildPaymentReference(String paymentMethod, String razorpayPaymentId, String razorpayOrderId) {
        String method = (paymentMethod == null || paymentMethod.isBlank()) ? "CASH" : paymentMethod.toUpperCase();
        if ("RAZORPAY".equals(method)) {
            if (razorpayPaymentId != null && !razorpayPaymentId.isBlank()) {
                return "RAZORPAY:" + razorpayPaymentId;
            }
            if (razorpayOrderId != null && !razorpayOrderId.isBlank()) {
                return "RAZORPAY:" + razorpayOrderId;
            }
        }
        return method;
    }

    private void linkCustomerToOrder(Order order) {
        if (order.getCustomerId() == null || order.getClientId() == null || order.getId() == null) {
            return;
        }
        customerRepository.findByIdAndClientId(order.getCustomerId(), order.getClientId()).ifPresent(customer -> {
            if (customer.getOrderLinks() == null) {
                customer.setOrderLinks(new ArrayList<>());
            }
            customer.getOrderLinks().removeIf(link -> order.getId().equals(link.getOrderId()));
            customer.getOrderLinks().add(Customer.OrderLink.builder()
                    .orderId(order.getId())
                    .isPrimary(true)
                    .attachedAt(Instant.now().toString())
                    .build());
            customerRepository.save(customer);
        });
    }

    private void validateSubscription(UUID clientId, UUID orgId) {
        if (clientId != null) {
            com.restaurant.pos.client.domain.Client client = clientRepository.findById(clientId).orElse(null);
            if (client == null || !client.isSubscriptionActive()) {
                throw new BusinessException("The restaurant's subscription has expired. Online menu and ordering are currently unavailable.");
            }
            
            // Validate TABLE_QR module
            boolean tableQrActive = false;
            String status = client.getSubscriptionStatus();
            if (status != null && "TRIAL".equalsIgnoreCase(status.trim()) && client.getSubscriptionExpiryDate() != null && client.getSubscriptionExpiryDate().isAfter(java.time.LocalDateTime.now())) {
                tableQrActive = true;
            } else {
                tableQrActive = systemConfigurationService.isModuleActive(clientId, orgId, com.restaurant.pos.subscription.domain.ModuleName.TABLE_QR);
            }
            
            if (!tableQrActive) {
                throw new BusinessException("Online Ordering is not active for this branch. Please contact restaurant administration.");
            }
        }
    }
}
