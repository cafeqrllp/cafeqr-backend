package com.restaurant.pos.integration.service;

import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.client.repository.OrganizationRepository;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.integration.dto.PartnerOrderCancelDto;
import com.restaurant.pos.integration.dto.PartnerOrderRequestDto;
import com.restaurant.pos.integration.dto.StoreMenuExportDto;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.domain.PaymentStatus;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.print.domain.PrintJobKind;
import com.restaurant.pos.print.service.PrintJobService;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.sequence.domain.DocumentType;
import com.restaurant.pos.sequence.service.DocumentSequenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerIntegrationService {

    private final OrganizationRepository organizationRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final DocumentSequenceService sequenceService;
    private final PrintJobService printJobService;

    @Value("${cafeqr.integrations.default-webhook-secret:${CAFEQR_WEBHOOK_SECRET:cafeqr_partner_secret_2026}}")
    private String defaultWebhookSecret;

    /**
     * Validates incoming partner request signature and timestamp.
     */
    public void validatePartnerRequest(String rawBody, String partnerKey, UUID storeId, Long timestamp, String signature) {
        if (timestamp == null || Math.abs(Instant.now().getEpochSecond() - timestamp) > 300) {
            throw new BusinessException("Request timestamp expired or out of tolerance window (300s).");
        }

        if (signature == null || signature.isBlank()) {
            throw new BusinessException("Missing X-CafeQR-Signature header.");
        }

        String secret = resolveStoreWebhookSecret(storeId);
        String expectedSignature = hmacSha256Hex(rawBody, secret);

        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Partner HMAC signature mismatch for store {}, partnerKey {}", storeId, partnerKey);
            throw new BusinessException("Invalid X-CafeQR-Signature. HMAC verification failed.");
        }
    }

    /**
     * Ingests an incoming order from Foodiyo or any partner platform.
     */
    @Transactional
    public Order ingestPartnerOrder(PartnerOrderRequestDto request) {
        Organization store = organizationRepository.findById(request.getStoreId())
                .orElseThrow(() -> new ResourceNotFoundException("Store not found: " + request.getStoreId()));

        // Check if order was already received (idempotency check)
        Optional<Order> existingOrder = orderRepository.findByOrgIdAndReference(store.getId(), request.getPartnerOrderId());
        if (existingOrder.isPresent()) {
            log.info("Partner order {} already exists in store {}, returning existing order", request.getPartnerOrderId(), store.getId());
            return existingOrder.get();
        }

        // Format remarks with customer delivery details for standard POS & KOT parser
        StringBuilder remarks = new StringBuilder();
        if (request.getCustomer() != null) {
            PartnerOrderRequestDto.CustomerDto cust = request.getCustomer();
            if (cust.getName() != null) remarks.append("name:").append(cust.getName()).append(" ");
            if (cust.getPhone() != null) remarks.append("phone:").append(cust.getPhone()).append(" ");
            if (cust.getDeliveryAddress() != null) remarks.append("address:").append(cust.getDeliveryAddress()).append(" ");
        }
        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            remarks.append("note:").append(request.getNotes());
        }

        String fulfillment = request.getFulfillmentType() != null ? request.getFulfillmentType().toUpperCase() : "DELIVERY";
        boolean isPrepaid = "ONLINE_PREPAID".equalsIgnoreCase(request.getPaymentMode());

        Order order = Order.builder()
                .orderType(OrderType.SALE)
                .orderStatus("CONFIRMED")
                .paymentStatus(isPrepaid ? PaymentStatus.PAID.name() : PaymentStatus.PENDING.name())
                .orderSource("APP")
                .syncOrigin("CLOUD_ONLINE")
                .fulfillmentType(fulfillment)
                .reference(request.getPartnerOrderId())
                .remarks(remarks.toString().trim())
                .lines(new ArrayList<>())
                .orderDate(Instant.now())
                .build();
        order.setClientId(store.getClientId());
        order.setOrgId(store.getId());

        // Assign human-readable sale order number
        order.setOrderNo(sequenceService.generateNextSequence(DocumentType.SALE_ORDER, store.getId()));

        // Process order lines
        BigDecimal totalGross = BigDecimal.ZERO;
        if (request.getItems() != null) {
            for (PartnerOrderRequestDto.ItemDto itemDto : request.getItems()) {
                BigDecimal qty = BigDecimal.valueOf(itemDto.getQuantity() != null ? itemDto.getQuantity() : 1);
                BigDecimal unitPrice = itemDto.getUnitPrice() != null ? itemDto.getUnitPrice() : BigDecimal.ZERO;
                BigDecimal lineTotal = itemDto.getTotalPrice() != null ? itemDto.getTotalPrice() : unitPrice.multiply(qty);

                UUID productId = null;
                if (itemDto.getExternalItemId() != null) {
                    try {
                        productId = UUID.fromString(itemDto.getExternalItemId());
                    } catch (Exception ignored) {}
                }

                String displayName = (itemDto.getVariantName() != null && !itemDto.getVariantName().isBlank())
                        ? itemDto.getItemName() + " (" + itemDto.getVariantName() + ")"
                        : itemDto.getItemName();

                OrderLine line = OrderLine.builder()
                        .order(order)
                        .productId(productId)
                        .productName(displayName)
                        .quantity(qty)
                        .unitPrice(unitPrice)
                        .lineTotal(lineTotal)
                        .grossLineAmount(lineTotal)
                        .build();

                order.getLines().add(line);
                totalGross = totalGross.add(lineTotal);
            }
        }

        // Apply financial totals
        if (request.getBillDetails() != null) {
            PartnerOrderRequestDto.BillDetailsDto bill = request.getBillDetails();
            order.setGrossAmount(bill.getSubtotal() != null ? bill.getSubtotal() : totalGross);
            order.setTotalDiscountAmount(bill.getDiscountAmount() != null ? bill.getDiscountAmount() : BigDecimal.ZERO);
            order.setTotalTaxAmount(bill.getTaxAmount() != null ? bill.getTaxAmount() : BigDecimal.ZERO);
            order.setGrandTotal(bill.getGrandTotal() != null ? bill.getGrandTotal() : totalGross);
            order.setTotalAmount(bill.getGrandTotal() != null ? bill.getGrandTotal() : totalGross);
        } else {
            order.setGrossAmount(totalGross);
            order.setGrandTotal(totalGross);
            order.setTotalAmount(totalGross);
        }

        Order savedOrder = orderRepository.save(order);

        // Auto-enqueue KOT print job to thermal kitchen printer
        try {
            printJobService.enqueueForOrder(savedOrder, PrintJobKind.KOT, "auto");
            log.info("Auto KOT print job enqueued for partner order {}", savedOrder.getOrderNo());
        } catch (Exception ex) {
            log.warn("Could not enqueue auto KOT print job for partner order {}: {}", savedOrder.getId(), ex.getMessage());
        }

        return savedOrder;
    }

    /**
     * Cancels an order received from an external partner.
     */
    @Transactional
    public Order cancelPartnerOrder(PartnerOrderCancelDto request) {
        Organization store = organizationRepository.findById(request.getStoreId())
                .orElseThrow(() -> new ResourceNotFoundException("Store not found: " + request.getStoreId()));

        Order order = orderRepository.findByOrgIdAndReference(store.getId(), request.getPartnerOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with partner ID: " + request.getPartnerOrderId()));

        order.setOrderStatus("CANCELLED");
        order.setRemarks((order.getRemarks() != null ? order.getRemarks() + " | " : "") + "Cancelled by partner: " + request.getCancelReason());
        return orderRepository.save(order);
    }

    /**
     * Exports active store catalog and prices for partner sync.
     */
    @Transactional(readOnly = true)
    public StoreMenuExportDto exportStoreMenu(UUID storeId) {
        Organization store = organizationRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found: " + storeId));

        List<Product> products = productRepository.findByClientIdAndOrgIdOrGlobal(store.getClientId(), store.getId());

        Map<String, List<StoreMenuExportDto.ItemDto>> itemsByCategory = new LinkedHashMap<>();

        for (Product product : products) {
            if (!product.isActive()) continue;

            String catName = product.getCategory() != null ? product.getCategory().getName() : "General";

            StoreMenuExportDto.ItemDto itemDto = StoreMenuExportDto.ItemDto.builder()
                    .itemId(product.getId().toString())
                    .itemName(product.getName())
                    .description(product.getDescription())
                    .price(product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO)
                    .isAvailable(product.isActive())
                    .taxRatePercent(product.getTaxRate() != null ? product.getTaxRate() : BigDecimal.ZERO)
                    .variants(new ArrayList<>())
                    .build();

            itemsByCategory.computeIfAbsent(catName, k -> new ArrayList<>()).add(itemDto);
        }

        List<StoreMenuExportDto.CategoryDto> categoryDtos = new ArrayList<>();
        int index = 1;
        for (Map.Entry<String, List<StoreMenuExportDto.ItemDto>> entry : itemsByCategory.entrySet()) {
            categoryDtos.add(StoreMenuExportDto.CategoryDto.builder()
                    .categoryId("CAT-" + index)
                    .categoryName(entry.getKey())
                    .displayOrder(index)
                    .items(entry.getValue())
                    .build());
            index++;
        }

        return StoreMenuExportDto.builder()
                .storeId(store.getId())
                .currency("INR")
                .categories(categoryDtos)
                .build();
    }

    private String resolveStoreWebhookSecret(UUID storeId) {
        return defaultWebhookSecret;
    }

    private String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((payload != null ? payload : "").getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new BusinessException("HMAC computation error: " + ex.getMessage());
        }
    }
}
