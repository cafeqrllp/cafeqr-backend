package com.restaurant.pos.push.service;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.SendResponse;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.push.domain.PushDeviceToken;
import com.restaurant.pos.push.repository.PushDeviceTokenRepository;
import com.restaurant.pos.push.dto.PushSubscribeRequest;
import com.restaurant.pos.push.dto.PushPreferencesRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class PushNotificationService {

    private final PushDeviceTokenRepository pushDeviceTokenRepository;
    private final FirebaseAdminService firebaseAdminService;

    @Transactional
    public void subscribe(UUID clientId, UUID userId, PushSubscribeRequest request) {
        Optional<PushDeviceToken> existingOpt = pushDeviceTokenRepository.findByDeviceToken(request.getDeviceToken());
        PushDeviceToken token;
        if (existingOpt.isPresent()) {
            token = existingOpt.get();
            token.setClientId(clientId);
            token.setUserId(userId);
            token.setPlatform(request.getPlatform());
            token.setEnabled(true);
            if (request.getNotifyKitchen() != null) token.setNotifyKitchen(request.getNotifyKitchen());
            if (request.getNotifyTakeaway() != null) token.setNotifyTakeaway(request.getNotifyTakeaway());
            if (request.getNotifyDelivery() != null) token.setNotifyDelivery(request.getNotifyDelivery());
            if (request.getNotifySettled() != null) token.setNotifySettled(request.getNotifySettled());
        } else {
            token = PushDeviceToken.builder()
                    .deviceToken(request.getDeviceToken())
                    .platform(request.getPlatform())
                    .userId(userId)
                    .enabled(true)
                    .notifyKitchen(request.getNotifyKitchen() != null ? request.getNotifyKitchen() : true)
                    .notifyTakeaway(request.getNotifyTakeaway() != null ? request.getNotifyTakeaway() : true)
                    .notifyDelivery(request.getNotifyDelivery() != null ? request.getNotifyDelivery() : true)
                    .notifySettled(request.getNotifySettled() != null ? request.getNotifySettled() : true)
                    .build();
            // ClientId and OrgId are set automatically by BaseEntity if present in TenantContext,
            // but we can set them explicitly to be safe.
            token.setClientId(clientId);
        }
        pushDeviceTokenRepository.save(token);
        log.info("Successfully subscribed device token for user: {}", userId);
    }

    @Transactional
    public void unsubscribe(String deviceToken) {
        pushDeviceTokenRepository.findByDeviceToken(deviceToken).ifPresent(token -> {
            token.setEnabled(false);
            pushDeviceTokenRepository.save(token);
            log.info("Successfully unsubscribed device token.");
        });
    }

    @Transactional
    public void updatePreferences(String deviceToken, PushPreferencesRequest request) {
        pushDeviceTokenRepository.findByDeviceToken(deviceToken).ifPresent(token -> {
            if (request.getNotifyKitchen() != null) token.setNotifyKitchen(request.getNotifyKitchen());
            if (request.getNotifyTakeaway() != null) token.setNotifyTakeaway(request.getNotifyTakeaway());
            if (request.getNotifyDelivery() != null) token.setNotifyDelivery(request.getNotifyDelivery());
            if (request.getNotifySettled() != null) token.setNotifySettled(request.getNotifySettled());
            pushDeviceTokenRepository.save(token);
            log.info("Successfully updated notification preferences for device token.");
        });
    }

    public Optional<PushDeviceToken> getStatus(String deviceToken) {
        return pushDeviceTokenRepository.findByDeviceToken(deviceToken);
    }

    @Async
    public void sendNewOrderPush(Order order) {
        if (order == null || order.getClientId() == null) return;

        String category = order.getFulfillmentType() != null ? order.getFulfillmentType() : "DINE_IN";
        List<PushDeviceToken> devices = pushDeviceTokenRepository.findByClientIdAndEnabled(order.getClientId(), true);

        List<PushDeviceToken> targets = devices.stream()
                .filter(d -> {
                    if ("TAKEAWAY".equalsIgnoreCase(category) || "PARCEL".equalsIgnoreCase(category)) return d.isNotifyTakeaway();
                    if ("DELIVERY".equalsIgnoreCase(category)) return d.isNotifyDelivery();
                    // Default to kitchen
                    return d.isNotifyKitchen();
                })
                .toList();

        if (targets.isEmpty()) return;

        String friendlyCategory = capitalize(category.replace("_", " "));
        String title = String.format("New %s Order", friendlyCategory);
        String baseBody = String.format("Order #%s - Total: %s", order.getOrderNo(), order.getGrandTotal());
        
        String itemsSummary = buildItemsSummary(order);
        String body = baseBody;
        if (itemsSummary != null && !itemsSummary.isEmpty()) {
            body = baseBody + "\n" + itemsSummary;
        }

        // Determine the Android notification channel based on order category
        String channelId;
        if ("TAKEAWAY".equalsIgnoreCase(category) || "PARCEL".equalsIgnoreCase(category)) {
            channelId = "channel_takeaway";
        } else if ("DELIVERY".equalsIgnoreCase(category)) {
            channelId = "channel_delivery";
        } else {
            channelId = "channel_kitchen";
        }

        Map<String, String> data = new HashMap<>();
        data.put("orderId", order.getId() != null ? order.getId().toString() : "");
        data.put("orderNo", order.getOrderNo() != null ? order.getOrderNo() : "");
        data.put("type", "new_order");
        data.put("category", category);
        data.put("grandTotal", order.getGrandTotal() != null ? order.getGrandTotal().toString() : "0.00");
        data.put("restaurantId", order.getOrgId() != null ? order.getOrgId().toString() : "");
        data.put("itemsSummary", itemsSummary != null ? itemsSummary : "");

        // For DELIVERY orders: split tokens by platform so Android gets data-only messages
        // (triggering CafeQrMessagingService with Accept/Decline buttons) while Web gets
        // the standard notification+data message.
        if ("DELIVERY".equalsIgnoreCase(category)) {
            List<String> androidTokens = targets.stream()
                    .filter(d -> "android".equalsIgnoreCase(d.getPlatform()))
                    .map(PushDeviceToken::getDeviceToken)
                    .toList();
            List<String> webTokens = targets.stream()
                    .filter(d -> !"android".equalsIgnoreCase(d.getPlatform()))
                    .map(PushDeviceToken::getDeviceToken)
                    .toList();

            // Android: data-only → CafeQrMessagingService builds notification with Accept/Decline
            if (!androidTokens.isEmpty()) {
                BatchResponse androidResponse = firebaseAdminService.sendDataOnlyMulticast(title, body, new HashMap<>(data), androidTokens);
                cleanInvalidTokens(androidTokens, androidResponse);
            }

            // Web: standard notification+data → browser shows notification with service worker buttons
            if (!webTokens.isEmpty()) {
                BatchResponse webResponse = firebaseAdminService.sendMulticast(title, body, data, webTokens, channelId);
                cleanInvalidTokens(webTokens, webResponse);
            }
        } else {
            // Kitchen, Takeaway, Settle: unchanged behavior with notification+data
            List<String> tokens = targets.stream().map(PushDeviceToken::getDeviceToken).toList();
            BatchResponse response = firebaseAdminService.sendMulticast(title, body, data, tokens, channelId);
            cleanInvalidTokens(tokens, response);
        }
    }

    @Async
    public void sendOrderSettledPush(Order order) {
        if (order == null || order.getClientId() == null) return;

        List<PushDeviceToken> devices = pushDeviceTokenRepository.findByClientIdAndEnabled(order.getClientId(), true);
        List<PushDeviceToken> targets = devices.stream().filter(PushDeviceToken::isNotifySettled).toList();

        if (targets.isEmpty()) return;

        List<String> tokens = targets.stream().map(PushDeviceToken::getDeviceToken).toList();

        String title = "Order Settled";
        String baseBody = String.format("Order #%s has been paid/completed.", order.getOrderNo());
        
        String itemsSummary = buildItemsSummary(order);
        String body = baseBody;
        if (itemsSummary != null && !itemsSummary.isEmpty()) {
            body = baseBody + "\n" + itemsSummary;
        }

        Map<String, String> data = new HashMap<>();
        data.put("orderId", order.getId() != null ? order.getId().toString() : "");
        data.put("orderNo", order.getOrderNo() != null ? order.getOrderNo() : "");
        data.put("type", "order_settled");
        data.put("category", "SETTLED");
        data.put("grandTotal", order.getGrandTotal() != null ? order.getGrandTotal().toString() : "0.00");
        data.put("restaurantId", order.getOrgId() != null ? order.getOrgId().toString() : "");
        data.put("itemsSummary", itemsSummary != null ? itemsSummary : "");

        BatchResponse response = firebaseAdminService.sendMulticast(title, body, data, tokens, "channel_settle");
        cleanInvalidTokens(tokens, response);
    }

    private void cleanInvalidTokens(List<String> tokens, BatchResponse response) {
        if (response == null || response.getResponses() == null) return;
        List<SendResponse> responses = response.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            SendResponse res = responses.get(i);
            if (!res.isSuccessful()) {
                String token = tokens.get(i);
                log.info("Token failed to send, disabling: {}", token);
                pushDeviceTokenRepository.findByDeviceToken(token).ifPresent(d -> {
                    d.setEnabled(false);
                    pushDeviceTokenRepository.save(d);
                });
            }
        }
    }

    private String capitalize(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.substring(0, 1).toUpperCase() + text.substring(1).toLowerCase();
    }

    private String buildItemsSummary(Order order) {
        if (order == null || order.getLines() == null || order.getLines().isEmpty()) {
            return "";
        }
        List<String> lineSummaries = new java.util.ArrayList<>();
        for (OrderLine line : order.getLines()) {
            if (line.getQuantity() != null && line.getProductName() != null) {
                double qtyDouble = line.getQuantity().doubleValue();
                String qtyStr = (qtyDouble % 1 == 0) ? String.valueOf((int) qtyDouble) : line.getQuantity().stripTrailingZeros().toPlainString();
                lineSummaries.add(qtyStr + " x " + line.getProductName());
            }
        }
        return String.join(", ", lineSummaries);
    }
}
