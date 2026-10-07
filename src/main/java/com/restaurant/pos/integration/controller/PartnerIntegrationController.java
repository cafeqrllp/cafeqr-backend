package com.restaurant.pos.integration.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.integration.dto.*;
import com.restaurant.pos.integration.service.PartnerIntegrationService;
import com.restaurant.pos.order.domain.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/public/integrations/partner")
@RequiredArgsConstructor
public class PartnerIntegrationController {

    private final PartnerIntegrationService integrationService;
    private final ObjectMapper objectMapper;

    /**
     * Ingests an incoming order from a third-party partner app (e.g., Foodiyo, Swiggy, Zomato).
     */
    @PostMapping("/orders")
    public ResponseEntity<PartnerApiResponse<?>> ingestOrder(
            @RequestHeader(value = "X-CafeQR-Partner-Key", required = false, defaultValue = "UNKNOWN") String partnerKey,
            @RequestHeader(value = "X-CafeQR-Store-ID", required = true) UUID storeId,
            @RequestHeader(value = "X-CafeQR-Timestamp", required = false) Long timestamp,
            @RequestHeader(value = "X-CafeQR-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        try {
            // Validate HMAC signature & timestamp if signature header is provided
            if (signature != null && !signature.isBlank()) {
                integrationService.validatePartnerRequest(rawBody, partnerKey, storeId, timestamp, signature);
            }

            PartnerOrderRequestDto request = objectMapper.readValue(rawBody, PartnerOrderRequestDto.class);
            request.setStoreId(storeId);
            if (request.getPartnerCode() == null || request.getPartnerCode().isBlank()) {
                request.setPartnerCode(partnerKey);
            }

            Order savedOrder = integrationService.ingestPartnerOrder(request);

            Map<String, Object> responseData = Map.of(
                    "pos_order_id", savedOrder.getId(),
                    "pos_order_number", savedOrder.getOrderNo(),
                    "partner_order_id", savedOrder.getReference(),
                    "status", savedOrder.getOrderStatus(),
                    "received_at", Instant.now().toString()
            );

            return ResponseEntity.ok(PartnerApiResponse.ok("Order successfully received and sent to Kitchen Printer", responseData));
        } catch (Exception ex) {
            log.error("Failed to ingest partner order: {}", ex.getMessage(), ex);
            return ResponseEntity.badRequest().body(PartnerApiResponse.error(ex.getMessage()));
        }
    }

    /**
     * Cancels an order requested by an external partner.
     */
    @PostMapping("/orders/cancel")
    public ResponseEntity<PartnerApiResponse<?>> cancelOrder(
            @RequestHeader(value = "X-CafeQR-Partner-Key", required = false, defaultValue = "UNKNOWN") String partnerKey,
            @RequestHeader(value = "X-CafeQR-Store-ID", required = true) UUID storeId,
            @RequestHeader(value = "X-CafeQR-Timestamp", required = false) Long timestamp,
            @RequestHeader(value = "X-CafeQR-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        try {
            if (signature != null && !signature.isBlank()) {
                integrationService.validatePartnerRequest(rawBody, partnerKey, storeId, timestamp, signature);
            }

            PartnerOrderCancelDto request = objectMapper.readValue(rawBody, PartnerOrderCancelDto.class);
            request.setStoreId(storeId);

            Order cancelledOrder = integrationService.cancelPartnerOrder(request);

            Map<String, Object> responseData = Map.of(
                    "pos_order_id", cancelledOrder.getId(),
                    "status", cancelledOrder.getOrderStatus()
            );

            return ResponseEntity.ok(PartnerApiResponse.ok("Order marked as CANCELLED in POS", responseData));
        } catch (Exception ex) {
            log.error("Failed to cancel partner order: {}", ex.getMessage(), ex);
            return ResponseEntity.badRequest().body(PartnerApiResponse.error(ex.getMessage()));
        }
    }

    /**
     * Exports the active menu catalog and prices for a store.
     */
    @GetMapping("/stores/{storeId}/menu")
    public ResponseEntity<StoreMenuExportDto> getStoreMenu(@PathVariable UUID storeId) {
        StoreMenuExportDto menu = integrationService.exportStoreMenu(storeId);
        return ResponseEntity.ok(menu);
    }
}
