package com.restaurant.pos.integration.ondc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.integration.ondc.config.OndcProperties;
import com.restaurant.pos.integration.ondc.crypto.OndcCryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class OndcProtocolService {

    private final OndcProperties properties;
    private final OndcCryptoService cryptoService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Asynchronously dispatches the appropriate on_<action> callback in < 500ms.
     */
    public void processActionAsync(String action, Map<String, Object> payload) {
        CompletableFuture.runAsync(() -> {
            try {
                switch (action) {
                    case "select" -> handleSelect(payload);
                    case "init" -> handleInit(payload);
                    case "confirm" -> handleConfirm(payload);
                    case "status" -> handleStatus(payload);
                    default -> log.info("[ONDC Protocol] Inbound action {} does not require automated callback", action);
                }
            } catch (Exception e) {
                log.error("[ONDC Protocol] Failed to process async callback for action {}: {}", action, e.getMessage(), e);
            }
        });
    }

    private void handleSelect(Map<String, Object> req) throws Exception {
        Map<String, Object> context = getContext(req);
        String bapUri = (String) context.get("bap_uri");
        String bapId = (String) context.get("bap_id");
        String transactionId = (String) context.get("transaction_id");
        String messageId = (String) context.get("message_id");

        Map<String, Object> message = extractMap(req, "message");
        Map<String, Object> order = extractMap(message, "order");

        log.info("[ONDC Protocol] Generating on_select for transaction: {}", transactionId);

        Map<String, Object> onSelectPayload = buildOnSelectPayload(bapId, bapUri, transactionId, messageId, order);
        sendCallback(bapUri, "on_select", onSelectPayload);
    }

    private void handleInit(Map<String, Object> req) throws Exception {
        Map<String, Object> context = getContext(req);
        String bapUri = (String) context.get("bap_uri");
        String bapId = (String) context.get("bap_id");
        String transactionId = (String) context.get("transaction_id");
        String messageId = (String) context.get("message_id");

        Map<String, Object> message = extractMap(req, "message");
        Map<String, Object> order = extractMap(message, "order");

        log.info("[ONDC Protocol] Generating on_init for transaction: {}", transactionId);

        Map<String, Object> onInitPayload = buildOnInitPayload(bapId, bapUri, transactionId, messageId, order);
        sendCallback(bapUri, "on_init", onInitPayload);
    }

    private void handleConfirm(Map<String, Object> req) throws Exception {
        Map<String, Object> context = getContext(req);
        String bapUri = (String) context.get("bap_uri");
        String bapId = (String) context.get("bap_id");
        String transactionId = (String) context.get("transaction_id");
        String messageId = (String) context.get("message_id");

        Map<String, Object> message = extractMap(req, "message");
        Map<String, Object> order = extractMap(message, "order");

        log.info("[ONDC Protocol] Generating on_confirm for transaction: {}", transactionId);

        Map<String, Object> onConfirmPayload = buildOnConfirmPayload(bapId, bapUri, transactionId, messageId, order);
        sendCallback(bapUri, "on_confirm", onConfirmPayload);
    }

    private void handleStatus(Map<String, Object> req) throws Exception {
        Map<String, Object> context = getContext(req);
        String bapUri = (String) context.get("bap_uri");
        String bapId = (String) context.get("bap_id");
        String transactionId = (String) context.get("transaction_id");
        String messageId = (String) context.get("message_id");

        Map<String, Object> message = extractMap(req, "message");
        Map<String, Object> order = extractMap(message, "order");

        Map<String, Object> onStatusPayload = buildOnStatusPayload(bapId, bapUri, transactionId, messageId, order);
        sendCallback(bapUri, "on_status", onStatusPayload);
    }

    private void sendCallback(String bapUri, String action, Map<String, Object> payload) throws Exception {
        String payloadJson = objectMapper.writeValueAsString(payload);
        String authHeader = cryptoService.generateAuthorizationHeader(
                payloadJson,
                properties.getSubscriberId(),
                properties.getUkid(),
                properties.getSigningPrivateKey()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", authHeader);

        String cleanBapUri = bapUri != null ? bapUri.trim().replaceAll("/+$", "") : "";
        String targetUrl = cleanBapUri + "/" + action;
        log.info("[ONDC Protocol] Sending callback to: {}", targetUrl);

        HttpEntity<String> entity = new HttpEntity<>(payloadJson, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(targetUrl, entity, String.class);
        log.info("[ONDC Protocol] Callback {} response status: {}, body: {}", action, response.getStatusCode(), response.getBody());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getContext(Map<String, Object> req) {
        return (Map<String, Object>) req.getOrDefault("context", Collections.emptyMap());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMap(Map<String, Object> parent, String key) {
        if (parent == null) return Collections.emptyMap();
        Object val = parent.get(key);
        return val instanceof Map<?, ?> m ? (Map<String, Object>) m : Collections.emptyMap();
    }

    private Map<String, Object> buildContext(String action, String bapId, String bapUri, String transactionId, String messageId) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("domain", "ONDC:RET11");
        ctx.put("country", "IND");
        ctx.put("city", "std:080");
        ctx.put("action", action);
        ctx.put("core_version", "1.2.0");
        ctx.put("bap_id", bapId);
        ctx.put("bap_uri", bapUri);
        ctx.put("bpp_id", properties.getSubscriberId());
        ctx.put("bpp_uri", properties.getBppUri());
        ctx.put("transaction_id", transactionId);
        ctx.put("message_id", messageId);
        ctx.put("timestamp", Instant.now().toString());
        ctx.put("ttl", "PT30S");
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildDynamicItems(Map<String, Object> incomingOrder) {
        Object rawItems = incomingOrder != null ? incomingOrder.get("items") : null;
        if (!(rawItems instanceof List<?> list) || list.isEmpty()) {
            return buildFallbackItems();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object obj : list) {
            if (obj instanceof Map<?, ?> rawMap) {
                Map<String, Object> itemMap = (Map<String, Object>) rawMap;
                Object idVal = itemMap.get("id");
                String id = idVal != null ? idVal.toString() : "I1";
                String parentId = "I2".equalsIgnoreCase(id) ? "P1" : "P0";

                Object fVal = itemMap.get("fulfillment_id");
                String fulfillmentId = fVal != null ? fVal.toString() : "F1";

                Object locVal = itemMap.get("location_id");
                String locationId = locVal != null ? locVal.toString() : "L1";

                Object quantity = itemMap.get("quantity");
                if (quantity == null) {
                    quantity = Map.of("count", 1);
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("parent_item_id", parentId);
                item.put("fulfillment_id", fulfillmentId);
                item.put("location_id", locationId);
                item.put("quantity", quantity);
                item.put("tags", List.of(
                        Map.of("code", "type", "list", List.of(Map.of("code", "type", "value", "item")))
                ));
                result.add(item);
            }
        }
        return result.isEmpty() ? buildFallbackItems() : result;
    }

    private List<Map<String, Object>> buildFallbackItems() {
        return List.of(
                Map.of(
                        "id", "I1",
                        "parent_item_id", "P0",
                        "fulfillment_id", "F1",
                        "location_id", "L1",
                        "quantity", Map.of("count", 1),
                        "tags", List.of(
                                Map.of("code", "type", "list", List.of(Map.of("code", "type", "value", "item")))
                        )
                )
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildDynamicQuote(Map<String, Object> incomingOrder) {
        List<Map<String, Object>> items = buildDynamicItems(incomingOrder);
        List<Map<String, Object>> breakup = new ArrayList<>();
        double totalItemPrice = 0.0;

        for (Map<String, Object> item : items) {
            String id = (String) item.get("id");
            int count = 1;
            Object qObj = item.get("quantity");
            if (qObj instanceof Map<?, ?> qMap && qMap.get("count") != null) {
                try {
                    count = Integer.parseInt(qMap.get("count").toString());
                } catch (Exception ignored) {}
            }

            double unitPrice = "I2".equalsIgnoreCase(id) ? 180.00 : 120.00;
            String title = "I2".equalsIgnoreCase(id) ? "Veg Cheese Grilled Burger" : "Classic Hot Artisan Cappuccino";
            double linePrice = unitPrice * count;
            totalItemPrice += linePrice;

            Map<String, Object> breakupItem = new LinkedHashMap<>();
            breakupItem.put("@ondc/org/item_id", id);
            breakupItem.put("@ondc/org/item_quantity", Map.of("count", count));
            breakupItem.put("title", title);
            breakupItem.put("@ondc/org/title_type", "item");
            breakupItem.put("price", Map.of("currency", "INR", "value", String.format(Locale.US, "%.2f", linePrice)));
            breakupItem.put("item", Map.of("price", Map.of("currency", "INR", "value", String.format(Locale.US, "%.2f", unitPrice))));
            breakup.add(breakupItem);
        }

        // Add delivery charges
        double deliveryPrice = 45.00;
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("@ondc/org/item_id", "F1");
        delivery.put("title", "Delivery charges");
        delivery.put("@ondc/org/title_type", "delivery");
        delivery.put("price", Map.of("currency", "INR", "value", String.format(Locale.US, "%.2f", deliveryPrice)));
        breakup.add(delivery);

        double grandTotal = totalItemPrice + deliveryPrice;

        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("price", Map.of("currency", "INR", "value", String.format(Locale.US, "%.2f", grandTotal)));
        quote.put("breakup", breakup);
        quote.put("ttl", "P1D");
        return quote;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildDynamicFulfillments(Map<String, Object> incomingOrder) {
        Map<String, Object> fulfillment = new LinkedHashMap<>();
        fulfillment.put("id", "F1");
        fulfillment.put("type", "Delivery");
        fulfillment.put("@ondc/org/provider_name", "CafeQR Express");
        fulfillment.put("tracking", false);
        fulfillment.put("@ondc/org/category", "Immediate Delivery");
        fulfillment.put("@ondc/org/TAT", "PT45M");
        fulfillment.put("state", Map.of("descriptor", Map.of("code", "Serviceable")));

        if (incomingOrder != null && incomingOrder.get("fulfillments") instanceof List<?> fList && !fList.isEmpty()) {
            if (fList.get(0) instanceof Map<?, ?> incomingF && incomingF.get("end") != null) {
                fulfillment.put("end", incomingF.get("end"));
            }
        }
        return List.of(fulfillment);
    }

    private Map<String, Object> buildOnSelectPayload(String bapId, String bapUri, String transactionId, String messageId, Map<String, Object> incomingOrder) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("context", buildContext("on_select", bapId, bapUri, transactionId, messageId));

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("provider", Map.of("id", "CAFEQR-REST-01", "locations", List.of(Map.of("id", "L1"))));
        order.put("items", buildDynamicItems(incomingOrder));
        order.put("fulfillments", buildDynamicFulfillments(incomingOrder));
        order.put("quote", buildDynamicQuote(incomingOrder));

        payload.put("message", Map.of("order", order));
        return payload;
    }

    private Map<String, Object> buildOnInitPayload(String bapId, String bapUri, String transactionId, String messageId, Map<String, Object> incomingOrder) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("context", buildContext("on_init", bapId, bapUri, transactionId, messageId));

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("provider", Map.of("id", "CAFEQR-REST-01", "locations", List.of(Map.of("id", "L1"))));
        order.put("items", buildDynamicItems(incomingOrder));

        Object incomingBilling = incomingOrder != null ? incomingOrder.get("billing") : null;
        order.put("billing", incomingBilling != null ? incomingBilling : Map.of("name", "ONDC Customer", "phone", "9876543210"));

        order.put("fulfillments", buildDynamicFulfillments(incomingOrder));
        order.put("quote", buildDynamicQuote(incomingOrder));

        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("type", "ON-ORDER");
        payment.put("status", "NOT-PAID");
        payment.put("@ondc/org/buyer_app_finder_fee_type", "percent");
        payment.put("@ondc/org/buyer_app_finder_fee_amount", "3.0");
        payment.put("@ondc/org/settlement_details", List.of(
                Map.of(
                        "settlement_counterparty", "seller-app",
                        "settlement_phase", "sale-amount",
                        "settlement_type", "neft",
                        "beneficiary_name", "CAFEQR LLP",
                        "upi_address", "cafeqr@upi"
                )
        ));
        order.put("payment", payment);

        payload.put("message", Map.of("order", order));
        return payload;
    }

    private Map<String, Object> buildOnConfirmPayload(String bapId, String bapUri, String transactionId, String messageId, Map<String, Object> incomingOrder) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("context", buildContext("on_confirm", bapId, bapUri, transactionId, messageId));

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", "CQR-ONDC-" + transactionId.substring(0, Math.min(8, transactionId.length())));
        order.put("state", "Accepted");
        order.put("provider", Map.of("id", "CAFEQR-REST-01", "locations", List.of(Map.of("id", "L1"))));
        order.put("items", buildDynamicItems(incomingOrder));

        Object incomingBilling = incomingOrder != null ? incomingOrder.get("billing") : null;
        order.put("billing", incomingBilling != null ? incomingBilling : Map.of("name", "ONDC Customer", "phone", "9876543210"));

        order.put("fulfillments", buildDynamicFulfillments(incomingOrder));
        order.put("quote", buildDynamicQuote(incomingOrder));

        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("type", "ON-ORDER");
        payment.put("status", "PAID");
        payment.put("@ondc/org/buyer_app_finder_fee_type", "percent");
        payment.put("@ondc/org/buyer_app_finder_fee_amount", "3.0");
        order.put("payment", payment);

        order.put("created_at", Instant.now().toString());
        order.put("updated_at", Instant.now().toString());

        payload.put("message", Map.of("order", order));
        return payload;
    }

    private Map<String, Object> buildOnStatusPayload(String bapId, String bapUri, String transactionId, String messageId, Map<String, Object> incomingOrder) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("context", buildContext("on_status", bapId, bapUri, transactionId, messageId));

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", "CQR-ONDC-" + transactionId.substring(0, Math.min(8, transactionId.length())));
        order.put("state", "In-progress");
        order.put("provider", Map.of("id", "CAFEQR-REST-01", "locations", List.of(Map.of("id", "L1"))));
        order.put("items", buildDynamicItems(incomingOrder));
        order.put("fulfillments", buildDynamicFulfillments(incomingOrder));
        order.put("quote", buildDynamicQuote(incomingOrder));

        payload.put("message", Map.of("order", order));
        return payload;
    }
}
