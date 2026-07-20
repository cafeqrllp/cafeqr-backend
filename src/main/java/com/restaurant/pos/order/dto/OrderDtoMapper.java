package com.restaurant.pos.order.dto;

import com.restaurant.pos.order.domain.DiscountEngineVersion;
import com.restaurant.pos.order.domain.DiscountSource;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.domain.TaxType;
import com.restaurant.pos.purchasing.domain.PurchaseOrder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@lombok.RequiredArgsConstructor
public class OrderDtoMapper {

    private final com.restaurant.pos.auth.repository.UserRepository userRepository;
    private final com.restaurant.pos.common.context.TimezoneResolver timezoneResolver;
    private final com.restaurant.pos.order.repository.PaymentRepository paymentRepository;
    private final com.restaurant.pos.order.repository.PaymentSplitRepository paymentSplitRepository;

    private java.time.Instant toInstant(java.time.LocalDateTime ldt) {
        if (ldt == null)
            return null;
        return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
    }

    // ─────────────────────────────────────────────────────────────
    // Per-request caches (ThreadLocal so they never leak across threads)
    // ─────────────────────────────────────────────────────────────

    /**
     * Maps user UUID string → display name, populated in bulk before mapping lists.
     * Must be cleared after each batch via {@link #clearRequestCaches()}.
     */
    private final ThreadLocal<java.util.Map<String, String>> userNameCache =
            ThreadLocal.withInitial(java.util.HashMap::new);

    /**
     * Maps orderId → list of active payments, pre-loaded before mapping lists.
     * Avoids a per-order query when the payment method is MIXED.
     */
    private final ThreadLocal<java.util.Map<java.util.UUID, java.util.List<com.restaurant.pos.order.domain.Payment>>>
            paymentsByOrderId = ThreadLocal.withInitial(java.util.HashMap::new);

    /**
     * Maps paymentId → list of splits, pre-loaded before mapping lists.
     */
    private final ThreadLocal<java.util.Map<java.util.UUID, java.util.List<com.restaurant.pos.order.domain.PaymentSplit>>>
            splitsByPaymentId = ThreadLocal.withInitial(java.util.HashMap::new);

    /** Pre-populate user names for the given set of raw uid strings in ONE query. */
    public void warmUserCache(java.util.Collection<String> uidStrings) {
        java.util.Map<String, String> cache = userNameCache.get();
        java.util.List<java.util.UUID> uuids = new java.util.ArrayList<>();
        for (String uid : uidStrings) {
            if (uid == null || uid.isBlank() || "SYSTEM".equalsIgnoreCase(uid) || cache.containsKey(uid)) continue;
            try { uuids.add(java.util.UUID.fromString(uid)); } catch (Exception ignored) { cache.put(uid, uid); }
        }
        if (!uuids.isEmpty()) {
            userRepository.findAllById(uuids).forEach(u -> {
                String name = u.getFirstName()
                        + (u.getLastName() != null && !u.getLastName().isBlank() ? " " + u.getLastName() : "");
                cache.put(u.getId().toString(), name);
            });
        }
    }

    /** Pre-populate payment + split maps for a set of order IDs in TWO queries. */
    public void warmPaymentCache(java.util.Collection<java.util.UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return;
        java.util.Map<java.util.UUID, java.util.List<com.restaurant.pos.order.domain.Payment>> pMap = paymentsByOrderId.get();
        java.util.Map<java.util.UUID, java.util.List<com.restaurant.pos.order.domain.PaymentSplit>> sMap = splitsByPaymentId.get();

        java.util.List<com.restaurant.pos.order.domain.Payment> allPayments =
                paymentRepository.findByOrderIdIn(orderIds);
        java.util.List<java.util.UUID> paymentIds = new java.util.ArrayList<>();
        for (com.restaurant.pos.order.domain.Payment p : allPayments) {
            if (p.getOrderId() != null) {   // guard: skip orphaned payments with no order reference
                pMap.computeIfAbsent(p.getOrderId(), k -> new java.util.ArrayList<>()).add(p);
            }
            if (p.getId() != null
                    && "Y".equalsIgnoreCase(p.getIsactive())
                    && !"VOID".equalsIgnoreCase(p.getDocStatus())) {
                paymentIds.add(p.getId());
            }
        }
        if (!paymentIds.isEmpty()) {
            paymentSplitRepository.findByPaymentIdInOrderByCreatedAtAsc(paymentIds)
                    .forEach(s -> {
                        if (s.getPaymentId() != null) {   // guard: skip splits with no payment reference
                            sMap.computeIfAbsent(s.getPaymentId(), k -> new java.util.ArrayList<>()).add(s);
                        }
                    });
        }
    }

    /** Must be called after each batch mapping to prevent ThreadLocal leaks. */
    public void clearRequestCaches() {
        userNameCache.get().clear();
        paymentsByOrderId.get().clear();
        splitsByPaymentId.get().clear();
    }

    private String resolveUserDisplayName(String uidStr) {
        if (uidStr == null || uidStr.isBlank() || "SYSTEM".equalsIgnoreCase(uidStr)) {
            return "SYSTEM";
        }
        java.util.Map<String, String> cache = userNameCache.get();
        if (cache.containsKey(uidStr)) {
            return cache.get(uidStr);
        }
        try {
            java.util.UUID userId = java.util.UUID.fromString(uidStr);
            String name = userRepository.findById(userId)
                    .map(u -> u.getFirstName()
                            + (u.getLastName() != null && !u.getLastName().isBlank() ? " " + u.getLastName() : ""))
                    .orElse(uidStr);
            cache.put(uidStr, name);
            return name;
        } catch (Exception e) {
            return uidStr;
        }
    }

    public OrderResponseDto toResponseDto(Order order) {
        if (order == null)
            return null;

        List<OrderResponseDto.OrderLineResponseDto> lines = null;
        if (order.getLines() != null) {
            lines = order.getLines().stream()
                    .map(this::toLineResponseDto)
                    .toList();
        }

        BigDecimal cashAmount = null;
        BigDecimal onlineAmount = null;
        String referenceNo = order.getReference();

        if ("MIXED".equalsIgnoreCase(order.getPaymentMethod()) || "MIXED".equalsIgnoreCase(referenceNo)) {
            try {
                // Use pre-loaded cache when available (populated by warmPaymentCache), else fall back to DB
                java.util.Map<java.util.UUID, java.util.List<com.restaurant.pos.order.domain.Payment>> pCache = paymentsByOrderId.get();
                java.util.List<com.restaurant.pos.order.domain.Payment> payments = pCache.containsKey(order.getId())
                        ? pCache.get(order.getId())
                        : paymentRepository.findByOrderId(order.getId());

                java.util.Map<java.util.UUID, java.util.List<com.restaurant.pos.order.domain.PaymentSplit>> sCache = splitsByPaymentId.get();

                for (com.restaurant.pos.order.domain.Payment p : payments) {
                    if ("Y".equalsIgnoreCase(p.getIsactive()) && !"VOID".equalsIgnoreCase(p.getDocStatus())) {
                        java.util.List<com.restaurant.pos.order.domain.PaymentSplit> splits = sCache.containsKey(p.getId())
                                ? sCache.get(p.getId())
                                : paymentSplitRepository.findByPaymentIdOrderByCreatedAtAsc(p.getId());
                        for (com.restaurant.pos.order.domain.PaymentSplit s : splits) {
                            if ("CASH".equalsIgnoreCase(s.getPaymentMethod())) {
                                cashAmount = (cashAmount == null) ? s.getAmount() : cashAmount.add(s.getAmount());
                            } else {
                                onlineAmount = (onlineAmount == null) ? s.getAmount() : onlineAmount.add(s.getAmount());
                            }
                        }
                        if ((splits == null || splits.isEmpty()) && p.getAmountPaid() != null) {
                            BigDecimal half = p.getAmountPaid().divide(BigDecimal.valueOf(2), 2,
                                    java.math.RoundingMode.HALF_UP);
                            cashAmount = half;
                            onlineAmount = p.getAmountPaid().subtract(half);
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                // Safe guard
            }
        }

        return OrderResponseDto.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .orderType(order.getOrderType())
                .orderStatus(order.getOrderStatus())
                .paymentStatus(order.getPaymentStatus())
                .orderSource(order.getOrderSource())
                .tableId(order.getTableId())
                .tableNumber(order.getTableNumber())
                .warehouseId(order.getWarehouseId())
                .vendorId(order.getVendorId())
                .currencyId(order.getCurrencyId())
                .orderDate(order.getOrderDate())
                .totalTaxAmount(order.getTotalTaxAmount())
                .totalDiscountAmount(order.getTotalDiscountAmount())
                .totalAmount(order.getTotalAmount())
                .grandTotal(order.getGrandTotal())
                .fulfillmentType(order.getFulfillmentType())
                .description(order.getDescription())
                .reference(order.getReference())
                .customers(order.getCustomers())
                .isCredit(order.getIsCredit())
                .creditCustomerId(order.getCreditCustomerId())
                .invoiceNo(order.getInvoiceNo())
                .dailyBillNo(order.getDailyBillNo())
                .paymentNo(order.getPaymentNo())
                .paymentMethod(order.getPaymentMethod())
                .cashAmount(cashAmount)
                .onlineAmount(onlineAmount)
                .grossAmount(order.getGrossAmount())
                .roundOffAmount(order.getRoundOffAmount())
                .orderDiscountType(order.getOrderDiscountType())
                .orderDiscountValue(order.getOrderDiscountValue())
                .discountSource(order.getDiscountSource() != null ? order.getDiscountSource().name() : null)
                .lines(lines)
                .revisionNumber(order.getRevisionNumber())
                .originalOrderId(order.getOriginalOrderId())
                .createdBy(resolveUserDisplayName(order.getCreatedBy()))
                .updatedBy(resolveUserDisplayName(order.getUpdatedBy()))
                .timezone(timezoneResolver.resolveTimezone(order.getClientId(), order.getOrgId()).getId())
                .createdAt(toInstant(order.getCreatedAt()))
                .updatedAt(toInstant(order.getUpdatedAt()))
                .build();
    }

    public OrderResponseDto.OrderLineResponseDto toLineResponseDto(OrderLine line) {
        if (line == null)
            return null;
        return OrderResponseDto.OrderLineResponseDto.builder()
                .id(line.getId())
                .productId(line.getProductId())
                .variantId(line.getVariantId())
                .productName(line.getProductName())
                .unitOfMeasure(line.getUnitOfMeasure())
                .uomPrecision(line.getUomPrecision())
                .quantity(line.getQuantity())
                .unitPrice(line.getUnitPrice())
                .taxRate(line.getTaxRate())
                .taxAmount(line.getTaxAmount())
                .discountAmount(line.getDiscountAmount())
                .lineTotal(line.getLineTotal())
                .grossLineAmount(line.getGrossLineAmount())
                .unitPriceExTax(line.getUnitPriceExTax())
                .taxableAmount(line.getTaxableAmount())
                .taxType(line.getTaxType() != null ? line.getTaxType().name() : null)
                .taxSnapshotRate(line.getTaxSnapshotRate())
                .taxCode(line.getTaxCode())
                .taxName(line.getTaxName())
                .manualDiscountAmount(line.getManualDiscountAmount())
                .manualDiscountPercent(line.getManualDiscountPercent())
                .allocatedOrderDiscount(line.getAllocatedOrderDiscount())
                .isPackagedGood(line.getIsPackagedGood())
                .description(line.getDescription())
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // Helper methods
    // ─────────────────────────────────────────────────────────────

    private TaxType parseTaxType(String s) {
        if (s == null)
            return TaxType.NONE;
        try {
            return TaxType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TaxType.NONE;
        }
    }

    private DiscountSource parseDiscountSource(String s) {
        if (s == null)
            return DiscountSource.MANUAL;
        try {
            return DiscountSource.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DiscountSource.MANUAL;
        }
    }

    private void applyTaxLineFields(OrderLine line, CreateOrderRequest.CreateOrderLineRequest lineReq) {
        if (lineReq.getClientLineId() != null)
            line.setClientLineId(lineReq.getClientLineId());
        line.setGrossLineAmount(lineReq.getGrossLineAmount());
        line.setUnitPriceExTax(lineReq.getUnitPriceExTax());
        line.setTaxableAmount(lineReq.getTaxableAmount());
        line.setTaxType(parseTaxType(lineReq.getTaxType()));
        line.setTaxSnapshotRate(lineReq.getTaxSnapshotRate());
        line.setTaxCode(lineReq.getTaxCode());
        line.setTaxName(lineReq.getTaxName());
        line.setManualDiscountAmount(lineReq.getManualDiscountAmount());
        line.setManualDiscountPercent(lineReq.getManualDiscountPercent());
        line.setAllocatedOrderDiscount(lineReq.getAllocatedOrderDiscount());
    }

    private void applyTaxOrderFields(Order order, CreateOrderRequest request) {
        if (request.getGrossAmount() != null)
            order.setGrossAmount(request.getGrossAmount());
        if (request.getOrderDiscountType() != null)
            order.setOrderDiscountType(request.getOrderDiscountType());
        if (request.getOrderDiscountValue() != null)
            order.setOrderDiscountValue(request.getOrderDiscountValue());
        order.setDiscountSource(parseDiscountSource(request.getDiscountSource()));
        order.setDiscountCalculationVersion(DiscountEngineVersion.CURRENT);
    }

    public Order toEntity(CreateOrderRequest request) {
        if (request == null)
            return null;
        Order order;
        if (request.getOrderType() == OrderType.PURCHASE) {
            order = new PurchaseOrder();
        } else {
            order = new Order();
        }
        order.setOrderType(request.getOrderType());
        order.setOrderNo(request.getOrderNo());
        order.setOfflineInvoiceNo(request.getOfflineInvoiceNo());
        order.setOfflinePaymentNo(request.getOfflinePaymentNo());
        // Idempotency / offline-sync source fields
        if (request.getSourceLocalRef() != null)
            order.setSourceLocalRef(request.getSourceLocalRef());
        if (request.getSourceOperationId() != null)
            order.setSourceOperationId(request.getSourceOperationId());
        if (request.getSourceDeviceId() != null)
            order.setSourceDeviceId(request.getSourceDeviceId());
        if (request.getSourceTerminalId() != null)
            order.setSourceTerminalId(request.getSourceTerminalId());
        if (request.getSyncOrigin() != null)
            order.setSyncOrigin(request.getSyncOrigin());
        order.setTableId(request.getTableId());
        order.setTableNumber(request.getTableNumber());
        order.setWarehouseId(request.getWarehouseId());
        order.setVendorId(request.getVendorId());
        order.setPricelistId(request.getPricelistId());
        order.setCurrencyId(request.getCurrencyId());
        order.setFulfillmentType(request.getFulfillmentType() != null ? request.getFulfillmentType() : "DINE_IN");
        order.setCustomerIds(request.getCustomerIds());
        order.setIsCredit(Boolean.TRUE.equals(request.getIsCredit()));
        order.setCreditCustomerId(request.getCreditCustomerId());
        order.setDescription(request.getDescription());
        order.setReference(request.getReference());
        order.setPaymentMethod(request.getPaymentMethod());
        if (request.getOrderDate() != null) {
            order.setOrderDate(request.getOrderDate());
        }
        if (request.getOrderStatus() != null && !request.getOrderStatus().isBlank()) {
            order.setOrderStatus(request.getOrderStatus());
        }
        if (request.getPaymentStatus() != null && !request.getPaymentStatus().isBlank()) {
            order.setPaymentStatus(request.getPaymentStatus());
        }
        if (request.getTotalAmount() != null) {
            order.setTotalAmount(request.getTotalAmount());
        }
        if (request.getTotalTaxAmount() != null) {
            order.setTotalTaxAmount(request.getTotalTaxAmount());
        }
        if (request.getTotalDiscountAmount() != null) {
            order.setTotalDiscountAmount(request.getTotalDiscountAmount());
        }
        if (request.getGrandTotal() != null) {
            order.setGrandTotal(request.getGrandTotal());
        }
        order.setSkipAutoPrintKinds(request.getSkipAutoPrintKinds());

        if (request.getLines() != null) {
            for (CreateOrderRequest.CreateOrderLineRequest lineReq : request.getLines()) {
                OrderLine line = new OrderLine();
                line.setProductId(lineReq.getProductId());
                line.setVariantId(lineReq.getVariantId());
                line.setProductName(lineReq.getProductName());
                line.setUnitOfMeasure(lineReq.getUnitOfMeasure());
                line.setUomPrecision(lineReq.getUomPrecision() != null ? lineReq.getUomPrecision() : 0);
                line.setQuantity(lineReq.getQuantity());
                line.setUnitPrice(lineReq.getUnitPrice());
                line.setTaxRate(lineReq.getTaxRate());
                line.setTaxAmount(lineReq.getTaxAmount());
                line.setDiscountAmount(lineReq.getDiscountAmount());
                line.setLineTotal(lineReq.getLineTotal());
                line.setDescription(lineReq.getDescription());
                applyTaxLineFields(line, lineReq);
                order.addLine(line);
            }
        }
        applyTaxOrderFields(order, request);

        // Transient payment fields for direct-settle orders (MIXED payment from
        // counter)
        if (request.getAmountPaid() != null)
            order.setAmountPaid(request.getAmountPaid());
        if (request.getRoundOffAmount() != null)
            order.setRoundOffAmount(request.getRoundOffAmount());
        if (request.getPaymentSplits() != null && !request.getPaymentSplits().isEmpty()) {
            order.setPaymentSplits(request.getPaymentSplits());
        }

        return order;
    }

    public Order applyUpdates(Order existing, UpdateOrderRequest request) {
        if (request == null)
            return existing;
        if (request.getTableId() != null)
            existing.setTableId(request.getTableId());
        if (request.getTableNumber() != null)
            existing.setTableNumber(request.getTableNumber());
        if (request.getWarehouseId() != null)
            existing.setWarehouseId(request.getWarehouseId());
        if (request.getVendorId() != null)
            existing.setVendorId(request.getVendorId());
        if (request.getOrderStatus() != null)
            existing.setOrderStatus(request.getOrderStatus());
        if (request.getPaymentStatus() != null)
            existing.setPaymentStatus(request.getPaymentStatus());
        if (request.getDescription() != null)
            existing.setDescription(request.getDescription());
        if (request.getReference() != null)
            existing.setReference(request.getReference());
        if (request.getPaymentMethod() != null)
            existing.setPaymentMethod(request.getPaymentMethod());
        if (request.getFulfillmentType() != null)
            existing.setFulfillmentType(request.getFulfillmentType());
        if (request.getCustomerIds() != null)
            existing.setCustomerIds(request.getCustomerIds());
        if (request.getIsCredit() != null)
            existing.setIsCredit(request.getIsCredit());
        if (request.getCreditCustomerId() != null)
            existing.setCreditCustomerId(request.getCreditCustomerId());
        if (request.getTotalAmount() != null)
            existing.setTotalAmount(request.getTotalAmount());
        if (request.getTotalTaxAmount() != null)
            existing.setTotalTaxAmount(request.getTotalTaxAmount());
        if (request.getTotalDiscountAmount() != null)
            existing.setTotalDiscountAmount(request.getTotalDiscountAmount());
        if (request.getGrandTotal() != null)
            existing.setGrandTotal(request.getGrandTotal());
        if (request.getRoundOffAmount() != null)
            existing.setRoundOffAmount(request.getRoundOffAmount());
        if (request.getSkipAutoPrintKinds() != null)
            existing.setSkipAutoPrintKinds(request.getSkipAutoPrintKinds());
        if (request.getGrossAmount() != null)
            existing.setGrossAmount(request.getGrossAmount());
        if (request.getOrderDiscountType() != null)
            existing.setOrderDiscountType(request.getOrderDiscountType());
        if (request.getOrderDiscountValue() != null)
            existing.setOrderDiscountValue(request.getOrderDiscountValue());
        if (request.getDiscountSource() != null)
            existing.setDiscountSource(parseDiscountSource(request.getDiscountSource()));

        if (request.getLines() != null) {
            existing.getLines().clear();
            for (CreateOrderRequest.CreateOrderLineRequest lineReq : request.getLines()) {
                OrderLine line = new OrderLine();
                line.setProductId(lineReq.getProductId());
                line.setVariantId(lineReq.getVariantId());
                line.setProductName(lineReq.getProductName());
                line.setUnitOfMeasure(lineReq.getUnitOfMeasure());
                line.setUomPrecision(lineReq.getUomPrecision() != null ? lineReq.getUomPrecision() : 0);
                line.setQuantity(lineReq.getQuantity());
                line.setUnitPrice(lineReq.getUnitPrice());
                line.setTaxRate(lineReq.getTaxRate());
                line.setTaxAmount(lineReq.getTaxAmount());
                line.setDiscountAmount(lineReq.getDiscountAmount());
                line.setLineTotal(lineReq.getLineTotal());
                line.setDescription(lineReq.getDescription());
                applyTaxLineFields(line, lineReq);
                existing.addLine(line);
            }
        }
        return existing;
    }

    public Order toEntity(UpdateOrderRequest request) {
        if (request == null)
            return null;
        Order order = new Order();
        order.setTableId(request.getTableId());
        order.setTableNumber(request.getTableNumber());
        order.setWarehouseId(request.getWarehouseId());
        order.setVendorId(request.getVendorId());
        order.setOrderStatus(request.getOrderStatus());
        order.setPaymentStatus(request.getPaymentStatus());
        order.setDescription(request.getDescription());
        order.setReference(request.getReference());
        order.setPaymentMethod(request.getPaymentMethod());
        order.setFulfillmentType(request.getFulfillmentType());
        order.setCustomerIds(request.getCustomerIds());
        order.setIsCredit(request.getIsCredit());
        order.setCreditCustomerId(request.getCreditCustomerId());
        if (request.getTotalAmount() != null) {
            order.setTotalAmount(request.getTotalAmount());
        }
        if (request.getTotalTaxAmount() != null) {
            order.setTotalTaxAmount(request.getTotalTaxAmount());
        }
        if (request.getTotalDiscountAmount() != null) {
            order.setTotalDiscountAmount(request.getTotalDiscountAmount());
        }
        if (request.getGrandTotal() != null) {
            order.setGrandTotal(request.getGrandTotal());
        }
        if (request.getRoundOffAmount() != null) {
            order.setRoundOffAmount(request.getRoundOffAmount());
        }
        order.setSkipAutoPrintKinds(request.getSkipAutoPrintKinds());
        if (request.getGrossAmount() != null) {
            order.setGrossAmount(request.getGrossAmount());
        }
        if (request.getOrderDiscountType() != null) {
            order.setOrderDiscountType(request.getOrderDiscountType());
        }
        if (request.getOrderDiscountValue() != null) {
            order.setOrderDiscountValue(request.getOrderDiscountValue());
        }
        if (request.getDiscountSource() != null) {
            order.setDiscountSource(parseDiscountSource(request.getDiscountSource()));
        }

        if (request.getLines() != null) {
            for (CreateOrderRequest.CreateOrderLineRequest lineReq : request.getLines()) {
                OrderLine line = new OrderLine();
                line.setProductId(lineReq.getProductId());
                line.setVariantId(lineReq.getVariantId());
                line.setProductName(lineReq.getProductName());
                line.setUnitOfMeasure(lineReq.getUnitOfMeasure());
                line.setQuantity(lineReq.getQuantity());
                line.setUnitPrice(lineReq.getUnitPrice());
                line.setTaxRate(lineReq.getTaxRate());
                line.setTaxAmount(lineReq.getTaxAmount());
                line.setDiscountAmount(lineReq.getDiscountAmount());
                line.setLineTotal(lineReq.getLineTotal());
                line.setDescription(lineReq.getDescription());
                applyTaxLineFields(line, lineReq);
                order.addLine(line);
            }
        }
        return order;
    }
}
