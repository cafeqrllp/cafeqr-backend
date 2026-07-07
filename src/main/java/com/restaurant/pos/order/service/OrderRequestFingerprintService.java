package com.restaurant.pos.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.order.dto.CreateOrderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Component
@RequiredArgsConstructor
public class OrderRequestFingerprintService {

    private final ObjectMapper objectMapper;

    public String fingerprint(CreateOrderRequest request) {
        try {
            Map<String, Object> canonical = new TreeMap<>();
            
            canonical.put("orderType", request.getOrderType() != null ? request.getOrderType().name() : null);
            canonical.put("fulfillmentType", normalizeCode(request.getFulfillmentType()));
            
            if (request.getWarehouseId() != null) canonical.put("warehouseId", request.getWarehouseId().toString());
            if (request.getVendorId() != null) canonical.put("vendorId", request.getVendorId().toString());
            if (request.getPricelistId() != null) canonical.put("pricelistId", request.getPricelistId().toString());
            if (request.getCurrencyId() != null) canonical.put("currencyId", request.getCurrencyId().toString());
            if (request.getTableId() != null) canonical.put("tableId", request.getTableId().toString());
            if (request.getCreditCustomerId() != null) canonical.put("creditCustomerId", request.getCreditCustomerId().toString());
            
            canonical.put("isCredit", request.getIsCredit());
            canonical.put("orderDate", normalizeInstant(request.getOrderDate()));
            
            // Input Discount configs
            canonical.put("orderDiscountType", normalizeCode(request.getOrderDiscountType()));
            canonical.put("orderDiscountValue", normalize(request.getOrderDiscountValue()));
            
            // Input Payments
            canonical.put("paymentMethod", normalizeCode(request.getPaymentMethod()));
            canonical.put("amountPaid", normalize(request.getAmountPaid()));

            // Normalize Lines inputs (not derived totals)
            if (request.getLines() != null) {
                List<Map<String, Object>> canonicalLines = new ArrayList<>();
                for (CreateOrderRequest.CreateOrderLineRequest line : request.getLines()) {
                    Map<String, Object> lineMap = new TreeMap<>();
                    if (line.getClientLineId() != null) lineMap.put("clientLineId", line.getClientLineId().toString());
                    if (line.getProductId() != null) lineMap.put("productId", line.getProductId().toString());
                    if (line.getVariantId() != null) lineMap.put("variantId", line.getVariantId().toString());
                    lineMap.put("quantity", normalize(line.getQuantity()));
                    lineMap.put("unitPrice", normalize(line.getUnitPrice()));
                    lineMap.put("taxRate", normalize(line.getTaxRate()));
                    lineMap.put("taxType", normalizeCode(line.getTaxType()));
                    
                    // Canonical line discount inputs
                    lineMap.put("lineDiscountType", resolveLineDiscountType(line));
                    lineMap.put("lineDiscountValue", normalize(resolveLineDiscountValue(line)));
                    
                    canonicalLines.add(lineMap);
                }
                canonical.put("lines", canonicalLines);
            }
            
            // Payment splits canonical sorting
            if (request.getPaymentSplits() != null) {
                List<Map<String, Object>> canonicalSplits = new ArrayList<>();
                for (CreateOrderRequest.PaymentSplitRequest split : request.getPaymentSplits()) {
                    Map<String, Object> splitMap = new TreeMap<>();
                    splitMap.put("paymentMethod", normalizeCode(split.getPaymentMethod()));
                    splitMap.put("amount", normalize(split.getAmount()));
                    splitMap.put("referenceNo", split.getReferenceNo());
                    canonicalSplits.add(splitMap);
                }
                canonicalSplits.sort((a, b) -> {
                    String methodA = a.get("paymentMethod") != null ? String.valueOf(a.get("paymentMethod")) : "";
                    String methodB = b.get("paymentMethod") != null ? String.valueOf(b.get("paymentMethod")) : "";
                    int c = methodA.compareTo(methodB);
                    if (c != 0) return c;
                    
                    String amtA = a.get("amount") != null ? String.valueOf(a.get("amount")) : "";
                    String amtB = b.get("amount") != null ? String.valueOf(b.get("amount")) : "";
                    c = amtA.compareTo(amtB);
                    if (c != 0) return c;
                    
                    String refA = a.get("referenceNo") != null ? String.valueOf(a.get("referenceNo")) : "";
                    String refB = b.get("referenceNo") != null ? String.valueOf(b.get("referenceNo")) : "";
                    return refA.compareTo(refB);
                });
                canonical.put("paymentSplits", canonicalSplits);
            }

            String json = objectMapper.writeValueAsString(canonical);
            return sha256(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate order request fingerprint", e);
        }
    }

    private String normalize(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private String normalizeInstant(Instant value) {
        return value == null ? null : value.toString();
    }

    private String normalizeCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String resolveLineDiscountType(CreateOrderRequest.CreateOrderLineRequest line) {
        if (line.getManualDiscountPercent() != null && line.getManualDiscountPercent().compareTo(BigDecimal.ZERO) > 0) {
            return "PERCENT";
        }
        if (line.getManualDiscountAmount() != null && line.getManualDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            return "AMOUNT";
        }
        return null;
    }

    private BigDecimal resolveLineDiscountValue(CreateOrderRequest.CreateOrderLineRequest line) {
        if (line.getManualDiscountPercent() != null && line.getManualDiscountPercent().compareTo(BigDecimal.ZERO) > 0) {
            return line.getManualDiscountPercent();
        }
        if (line.getManualDiscountAmount() != null && line.getManualDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            return line.getManualDiscountAmount();
        }
        return null;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
