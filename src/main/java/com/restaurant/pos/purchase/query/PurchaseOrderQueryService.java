package com.restaurant.pos.purchase.query;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.dto.OrderResponseDto;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.purchase.dto.PurchaseOrderSummaryDto;
import com.restaurant.pos.purchase.mapper.PurchaseOrderDtoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * CQRS Query Service for Purchase Orders.
 * searchPurchaseOrders() returns lightweight PurchaseOrderSummaryDto (no lines, no N+1 user/invoice queries).
 * getPurchaseOrder(id) returns the full OrderResponseDto with lines and all detail for the popup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderQueryService {

    private final OrderRepository orderRepository;
    private final PurchaseOrderDtoMapper purchaseOrderDtoMapper;
    private final com.restaurant.pos.order.service.OrderService orderService;
    private final com.restaurant.pos.purchasing.repository.VendorRepository vendorRepository;

    /**
     * Paginated purchase order history list mapped to lightweight PurchaseOrderSummaryDto.
     * Uses composite indexes and Specification filters to fetch orders cleanly.
     * Line items and lazy sub-queries are skipped for history listing.
     */
    @Transactional(readOnly = true)
    public Page<PurchaseOrderSummaryDto> searchPurchaseOrders(PurchaseOrderSearchRequest request, Pageable pageable) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = request.getBranchId() != null 
                ? request.getBranchId() 
                : (com.restaurant.pos.common.util.SecurityUtils.isSuperAdmin() ? null : TenantContext.getCurrentOrg());

        Specification<Order> spec = PurchaseOrderSpecifications.withFilters(request, clientId, orgId);
        Page<Order> pageResult = orderRepository.findAll(spec, pageable);
        return pageResult.map(purchaseOrderDtoMapper::toSummaryDto);
    }

    @Transactional(readOnly = true)
    public List<OrderResponseDto> getDraftPurchaseOrders() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        List<Order> drafts = (orgId != null)
                ? orderRepository.findByClientIdAndOrgIdAndOrderTypeOrderByCreatedAtDesc(clientId, orgId, OrderType.PURCHASE)
                : orderRepository.findByClientIdAndOrderTypeOrderByCreatedAtDesc(clientId, OrderType.PURCHASE);

        return drafts.stream()
                .filter(o -> "DRAFT".equalsIgnoreCase(o.getOrderStatus()))
                .map(purchaseOrderDtoMapper::toResponseDto)
                .toList();
    }

    /**
     * Returns the FULL purchase order DTO with line items, user names, and linked invoice/payment refs.
     * Called when the user opens a specific order in the detail popup.
     */
    @Transactional(readOnly = true)
    public OrderResponseDto getPurchaseOrder(UUID orderId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        Order order = (orgId != null)
                ? orderRepository.findByIdAndClientIdAndOrgId(orderId, clientId, orgId)
                    .orElseGet(() -> orderRepository.findByIdAndClientId(orderId, clientId)
                        .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found for ID: " + orderId)))
                : orderRepository.findByIdAndClientId(orderId, clientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found for ID: " + orderId));

        if (order.getOrderType() != null && order.getOrderType() != OrderType.PURCHASE) {
            throw new BusinessException("Order " + orderId + " is not a Purchase Order");
        }

        return purchaseOrderDtoMapper.toResponseDto(order);
    }

    /**
     * Returns revision history for a purchase order (current + VOID predecessors).
     */
    @Transactional(readOnly = true)
    public List<OrderResponseDto> getPurchaseOrderRevisions(UUID orderId) {
        UUID clientId = TenantContext.getCurrentTenant();
        Order current = orderRepository.findByIdAndClientId(orderId, clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order not found for ID: " + orderId));

        String baseOrderNo = current.getOrderNo();
        if (baseOrderNo != null && baseOrderNo.contains("_VOID_")) {
            baseOrderNo = baseOrderNo.substring(0, baseOrderNo.indexOf("_VOID_"));
        }
        String voidPrefix = baseOrderNo + "_VOID_%";

        return orderRepository.findAllRevisionsByOrderNo(clientId, baseOrderNo, voidPrefix)
                .stream()
                .map(purchaseOrderDtoMapper::toResponseDto)
                .toList();
    }

    /**
     * Returns payment splits for a settled mixed payment purchase order.
     */
    @Transactional(readOnly = true)
    public List<com.restaurant.pos.order.dto.PaymentSplitResponseDto> getPaymentSplits(UUID orderId) {
        return orderService.getPaymentSplits(orderId).stream()
                .map(ps -> new com.restaurant.pos.order.dto.PaymentSplitResponseDto(ps.getId(), ps.getPaymentMethod(), ps.getAmount(), ps.getReferenceNo()))
                .toList();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Purchase Reporting Methods
    // ─────────────────────────────────────────────────────────────────────────

    private List<Order> fetchFilteredPurchaseOrders(java.time.Instant from, java.time.Instant to, UUID orgId, UUID vendorId, UUID warehouseId, boolean fetchLines) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID resolvedOrgId = orgId != null
                ? orgId
                : (com.restaurant.pos.common.util.SecurityUtils.isSuperAdmin() ? null : TenantContext.getCurrentOrg());

        return orderRepository.findAll((root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (resolvedOrgId != null) {
                predicates.add(cb.equal(root.get("orgId"), resolvedOrgId));
            }
            if (vendorId != null) {
                predicates.add(cb.equal(root.get("vendorId"), vendorId));
            }
            if (warehouseId != null) {
                predicates.add(cb.equal(root.get("warehouseId"), warehouseId));
            }
            predicates.add(cb.equal(root.get("orderType"), OrderType.PURCHASE));
            predicates.add(cb.equal(root.get("isactive"), "Y"));
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("orderDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("orderDate"), to));
            }
            if (fetchLines && Long.class != query.getResultType() && long.class != query.getResultType()) {
                root.fetch("lines", jakarta.persistence.criteria.JoinType.LEFT);
                query.distinct(true);
            }
            query.orderBy(cb.desc(root.get("orderDate")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });
    }

    private java.math.BigDecimal safe(java.math.BigDecimal val) {
        return val != null ? val : java.math.BigDecimal.ZERO;
    }

    @Transactional(readOnly = true)
    public com.restaurant.pos.purchase.dto.PurchaseReportSummaryDto getPurchaseSummary(
            java.time.Instant from, java.time.Instant to, UUID orgId, UUID vendorId, UUID warehouseId) {
        List<Order> orders = fetchFilteredPurchaseOrders(from, to, orgId, vendorId, warehouseId, false);

        java.math.BigDecimal totalPurchases = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalPaid = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalUnpaid = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalTax = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalDiscount = java.math.BigDecimal.ZERO;
        long totalOrders = 0;
        long receivedOrders = 0;
        long draftOrders = 0;

        java.util.Map<String, Long> statusCounts = new java.util.HashMap<>();
        java.util.Map<String, Long> paymentStatusCounts = new java.util.HashMap<>();

        for (Order o : orders) {
            String status = o.getOrderStatus() != null ? o.getOrderStatus().toUpperCase() : "UNKNOWN";
            String payStatus = o.getPaymentStatus() != null ? o.getPaymentStatus().toUpperCase() : "UNPAID";

            totalOrders++;
            statusCounts.merge(status, 1L, Long::sum);
            paymentStatusCounts.merge(payStatus, 1L, Long::sum);

            if ("DRAFT".equals(status)) {
                draftOrders++;
            }
            if ("COMPLETED".equals(status) || Boolean.TRUE.equals(o.getIsReceived())) {
                receivedOrders++;
            }

            if (!"VOID".equals(status) && !"CANCELLED".equals(status)) {
                java.math.BigDecimal grandTotal = safe(o.getGrandTotal());
                totalPurchases = totalPurchases.add(grandTotal);
                totalTax = totalTax.add(safe(o.getTotalTaxAmount()));
                totalDiscount = totalDiscount.add(safe(o.getTotalDiscountAmount()));

                if ("PAID".equals(payStatus)) {
                    totalPaid = totalPaid.add(grandTotal);
                } else if ("PARTIAL".equals(payStatus)) {
                    java.math.BigDecimal half = grandTotal.divide(java.math.BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
                    totalPaid = totalPaid.add(half);
                    totalUnpaid = totalUnpaid.add(grandTotal.subtract(half));
                } else {
                    totalUnpaid = totalUnpaid.add(grandTotal);
                }
            }
        }

        long nonDraftCount = totalOrders - draftOrders;
        java.math.BigDecimal averageOrderValue = (nonDraftCount > 0 && totalPurchases.compareTo(java.math.BigDecimal.ZERO) > 0)
                ? totalPurchases.divide(java.math.BigDecimal.valueOf(nonDraftCount), 2, java.math.RoundingMode.HALF_UP)
                : java.math.BigDecimal.ZERO;

        return com.restaurant.pos.purchase.dto.PurchaseReportSummaryDto.builder()
                .totalPurchases(totalPurchases)
                .totalPaid(totalPaid)
                .totalUnpaid(totalUnpaid)
                .totalTax(totalTax)
                .totalDiscount(totalDiscount)
                .totalOrders(totalOrders)
                .receivedOrders(receivedOrders)
                .draftOrders(draftOrders)
                .averageOrderValue(averageOrderValue)
                .statusCounts(statusCounts)
                .paymentStatusCounts(paymentStatusCounts)
                .build();
    }

    @Transactional(readOnly = true)
    public List<com.restaurant.pos.purchase.dto.PurchaseItemReportDto> getPurchaseItemReport(
            java.time.Instant from, java.time.Instant to, UUID orgId, UUID vendorId, UUID warehouseId) {
        List<Order> orders = fetchFilteredPurchaseOrders(from, to, orgId, vendorId, warehouseId, true);

        class ItemAccumulator {
            String productName;
            String categoryName;
            String uom;
            Integer uomPrecision;
            java.math.BigDecimal totalQuantity = java.math.BigDecimal.ZERO;
            java.math.BigDecimal totalSpent = java.math.BigDecimal.ZERO;
            long orderCount = 0;
        }

        java.util.Map<String, ItemAccumulator> itemMap = new java.util.LinkedHashMap<>();

        for (Order o : orders) {
            String status = o.getOrderStatus() != null ? o.getOrderStatus().toUpperCase() : "";
            if ("VOID".equals(status) || "CANCELLED".equals(status)) {
                continue;
            }
            if (o.getLines() == null) continue;

            java.util.Set<String> itemsSeenInOrder = new java.util.HashSet<>();
            for (com.restaurant.pos.order.domain.OrderLine line : o.getLines()) {
                if (!line.isActive()) continue;
                String pName = line.getProductName() != null ? line.getProductName() : "Unknown Item";
                String cat = line.getCategoryName() != null ? line.getCategoryName() : "General";
                String uom = line.getUnitOfMeasure() != null ? line.getUnitOfMeasure() : "pcs";
                String key = pName + "|||" + cat + "|||" + uom;

                ItemAccumulator acc = itemMap.computeIfAbsent(key, k -> {
                    ItemAccumulator a = new ItemAccumulator();
                    a.productName = pName;
                    a.categoryName = cat;
                    a.uom = uom;
                    a.uomPrecision = line.getUomPrecision();
                    return a;
                });

                acc.totalQuantity = acc.totalQuantity.add(safe(line.getQuantity()));
                acc.totalSpent = acc.totalSpent.add(safe(line.getLineTotal()));

                if (itemsSeenInOrder.add(key)) {
                    acc.orderCount++;
                }
            }
        }

        return itemMap.values().stream()
                .map(acc -> {
                    java.math.BigDecimal avgCost = acc.totalQuantity.compareTo(java.math.BigDecimal.ZERO) > 0
                            ? acc.totalSpent.divide(acc.totalQuantity, 2, java.math.RoundingMode.HALF_UP)
                            : java.math.BigDecimal.ZERO;

                    return com.restaurant.pos.purchase.dto.PurchaseItemReportDto.builder()
                            .productName(acc.productName)
                            .categoryName(acc.categoryName)
                            .uom(acc.uom)
                            .uomPrecision(acc.uomPrecision)
                            .totalQuantity(acc.totalQuantity)
                            .totalSpent(acc.totalSpent)
                            .avgUnitCost(avgCost)
                            .orderCount(acc.orderCount)
                            .build();
                })
                .sorted((a, b) -> b.getTotalSpent().compareTo(a.getTotalSpent()))
                .collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<com.restaurant.pos.purchase.dto.PurchaseVendorReportDto> getPurchaseVendorReport(
            java.time.Instant from, java.time.Instant to, UUID orgId, UUID vendorId, UUID warehouseId) {
        List<Order> orders = fetchFilteredPurchaseOrders(from, to, orgId, vendorId, warehouseId, false);

        UUID clientId = TenantContext.getCurrentTenant();
        java.util.Map<UUID, String> vendorNameCache = new java.util.HashMap<>();
        try {
            vendorRepository.findByClientIdOrderByNameAsc(clientId).forEach(v -> {
                if (v.getId() != null) vendorNameCache.put(v.getId(), v.getName());
            });
        } catch (Exception ignored) {}

        class VendorAccumulator {
            UUID vendorId;
            String vendorName;
            long orderCount = 0;
            java.math.BigDecimal totalBilled = java.math.BigDecimal.ZERO;
            java.math.BigDecimal totalPaid = java.math.BigDecimal.ZERO;
            java.math.BigDecimal outstandingBalance = java.math.BigDecimal.ZERO;
            java.time.Instant lastOrderDate = null;
        }

        java.util.Map<UUID, VendorAccumulator> vendorMap = new java.util.LinkedHashMap<>();

        for (Order o : orders) {
            String status = o.getOrderStatus() != null ? o.getOrderStatus().toUpperCase() : "";
            if ("VOID".equals(status) || "CANCELLED".equals(status)) {
                continue;
            }

            UUID vId = o.getVendorId();
            if (vId == null) continue;

            VendorAccumulator acc = vendorMap.computeIfAbsent(vId, k -> {
                VendorAccumulator va = new VendorAccumulator();
                va.vendorId = vId;
                va.vendorName = vendorNameCache.getOrDefault(vId, "Vendor " + vId.toString().substring(0, 8));
                return va;
            });

            acc.orderCount++;
            java.math.BigDecimal grandTotal = safe(o.getGrandTotal());
            acc.totalBilled = acc.totalBilled.add(grandTotal);

            String payStatus = o.getPaymentStatus() != null ? o.getPaymentStatus().toUpperCase() : "UNPAID";
            if ("PAID".equals(payStatus)) {
                acc.totalPaid = acc.totalPaid.add(grandTotal);
            } else if ("PARTIAL".equals(payStatus)) {
                java.math.BigDecimal half = grandTotal.divide(java.math.BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
                acc.totalPaid = acc.totalPaid.add(half);
                acc.outstandingBalance = acc.outstandingBalance.add(grandTotal.subtract(half));
            } else {
                acc.outstandingBalance = acc.outstandingBalance.add(grandTotal);
            }

            java.time.Instant dt = o.getOrderDate() != null
                    ? o.getOrderDate()
                    : (o.getCreatedAt() != null ? o.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null);
            if (dt != null && (acc.lastOrderDate == null || dt.isAfter(acc.lastOrderDate))) {
                acc.lastOrderDate = dt;
            }
        }

        return vendorMap.values().stream()
                .map(va -> com.restaurant.pos.purchase.dto.PurchaseVendorReportDto.builder()
                        .vendorId(va.vendorId)
                        .vendorName(va.vendorName)
                        .orderCount(va.orderCount)
                        .totalBilled(va.totalBilled)
                        .totalPaid(va.totalPaid)
                        .outstandingBalance(va.outstandingBalance)
                        .lastOrderDate(va.lastOrderDate)
                        .build())
                .sorted((a, b) -> b.getTotalBilled().compareTo(a.getTotalBilled()))
                .collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<com.restaurant.pos.order.dto.report.PaymentBreakdownDto> getPurchasePaymentBreakdown(
            java.time.Instant from, java.time.Instant to, UUID orgId, UUID vendorId, UUID warehouseId) {
        List<Order> orders = fetchFilteredPurchaseOrders(from, to, orgId, vendorId, warehouseId, false);

        java.util.Map<String, java.math.BigDecimal[]> methodMap = new java.util.LinkedHashMap<>();
        java.math.BigDecimal totalAllPurchases = java.math.BigDecimal.ZERO;

        for (Order o : orders) {
            String status = o.getOrderStatus() != null ? o.getOrderStatus().toUpperCase() : "";
            if ("VOID".equals(status) || "CANCELLED".equals(status)) {
                continue;
            }

            String method = o.getPaymentMethod();
            if (method == null || method.isBlank()) {
                method = "CREDIT";
            } else {
                method = method.trim().toUpperCase();
            }

            java.math.BigDecimal amount = safe(o.getGrandTotal());
            totalAllPurchases = totalAllPurchases.add(amount);

            methodMap.computeIfAbsent(method, k -> new java.math.BigDecimal[]{java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO});
            java.math.BigDecimal[] entry = methodMap.get(method);
            entry[0] = entry[0].add(java.math.BigDecimal.ONE);
            entry[1] = entry[1].add(amount);
        }

        final java.math.BigDecimal grandTotal = totalAllPurchases;
        return methodMap.entrySet().stream()
                .map(e -> {
                    java.math.BigDecimal count = e.getValue()[0];
                    java.math.BigDecimal amount = e.getValue()[1];
                    java.math.BigDecimal pct = (grandTotal.compareTo(java.math.BigDecimal.ZERO) > 0)
                            ? amount.multiply(java.math.BigDecimal.valueOf(100)).divide(grandTotal, 2, java.math.RoundingMode.HALF_UP)
                            : java.math.BigDecimal.ZERO;

                    return com.restaurant.pos.order.dto.report.PaymentBreakdownDto.builder()
                            .paymentMethod(e.getKey())
                            .orderCount(count.longValue())
                            .totalAmount(amount)
                            .percentage(pct)
                            .build();
                })
                .sorted((a, b) -> b.getTotalAmount().compareTo(a.getTotalAmount()))
                .collect(java.util.stream.Collectors.toList());
    }
}
