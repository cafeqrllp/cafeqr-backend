package com.restaurant.pos.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.payment.dto.RazorpayOrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RazorpayService {

    private static final URI ORDERS_URI = URI.create("https://api.razorpay.com/v1/orders");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${razorpay.key-id:${RAZORPAY_KEY_ID:}}")
    private String keyId;

    @Value("${razorpay.key-secret:${RAZORPAY_KEY_SECRET:}}")
    private String keySecret;

    @Value("${razorpay.webhook-secret:${RAZORPAY_WEBHOOK_SECRET:}}")
    private String webhookSecret;

    public String getKeyId() {
        return keyId;
    }

    public RazorpayOrderResponse createOrder(BigDecimal amountRupees, String currency, String receipt, Map<String, Object> notes) {
        if (amountRupees == null || amountRupees.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Payment amount must be greater than zero");
        }

        BigDecimal paiseDecimal = amountRupees.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP);
        return createOrder(paiseDecimal.longValueExact(), currency, receipt, notes);
    }

    public RazorpayOrderResponse createOrder(long amountPaise, String currency, String receipt, Map<String, Object> notes) {
        ensureConfigured();

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("amount", amountPaise);
            payload.put("currency", (currency == null || currency.isBlank()) ? "INR" : currency);
            payload.put("receipt", normalizeReceipt(receipt));
            payload.put("payment_capture", 1);
            payload.put("notes", notes == null ? Map.of() : notes);

            HttpRequest request = HttpRequest.newBuilder(ORDERS_URI)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8)))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String description = body.path("error").path("description").asText("Failed to create Razorpay order");
                throw new BusinessException(description);
            }

            return RazorpayOrderResponse.builder()
                    .orderId(body.path("id").asText())
                    .amount(body.path("amount").asLong(amountPaise))
                    .currency(body.path("currency").asText((String) payload.get("currency")))
                    .keyId(keyId)
                    .receipt(body.path("receipt").asText((String) payload.get("receipt")))
                    .build();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Unable to create Razorpay order: " + ex.getMessage());
        }
    }

    public JsonNode fetchOrder(String orderId) {
        ensureConfigured();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.razorpay.com/v1/orders/" + orderId))
                    .header("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8)))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String description = body.path("error").path("description").asText("Failed to fetch Razorpay order");
                throw new BusinessException(description);
            }
            return body;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Unable to fetch Razorpay order: " + ex.getMessage());
        }
    }

    public RazorpayOrderResponse createOrderWithKeys(String customKeyId, String customKeySecret, BigDecimal amountRupees, String currency, String receipt, Map<String, Object> notes) {
        if (amountRupees == null || amountRupees.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Payment amount must be greater than zero");
        }
        BigDecimal paiseDecimal = amountRupees.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP);
        return createOrderWithKeys(customKeyId, customKeySecret, paiseDecimal.longValueExact(), currency, receipt, notes);
    }

    public RazorpayOrderResponse createOrderWithKeys(String customKeyId, String customKeySecret, long amountPaise, String currency, String receipt, Map<String, Object> notes) {
        if (isBlank(customKeyId) || isBlank(customKeySecret)) {
            throw new BusinessException("Restaurant payment gateway credentials are not configured.");
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("amount", amountPaise);
            payload.put("currency", (currency == null || currency.isBlank()) ? "INR" : currency);
            payload.put("receipt", normalizeReceipt(receipt));
            payload.put("payment_capture", 1);
            payload.put("notes", notes == null ? Map.of() : notes);

            HttpRequest request = HttpRequest.newBuilder(ORDERS_URI)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString((customKeyId + ":" + customKeySecret).getBytes(StandardCharsets.UTF_8)))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = objectMapper.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String description = body.path("error").path("description").asText("Failed to create Razorpay order");
                throw new BusinessException(description);
            }

            return RazorpayOrderResponse.builder()
                    .orderId(body.path("id").asText())
                    .amount(body.path("amount").asLong(amountPaise))
                    .currency(body.path("currency").asText((String) payload.get("currency")))
                    .keyId(customKeyId)
                    .receipt(body.path("receipt").asText((String) payload.get("receipt")))
                    .build();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Unable to create payment order: " + ex.getMessage());
        }
    }

    public boolean verifyPaymentSignature(String orderId, String paymentId, String signature) {
        ensureConfigured();
        return verifyPaymentSignatureWithSecret(orderId, paymentId, signature, keySecret);
    }

    public boolean verifyPaymentSignatureWithSecret(String orderId, String paymentId, String signature, String customKeySecret) {
        if (isBlank(customKeySecret) || isBlank(orderId) || isBlank(paymentId) || isBlank(signature)) {
            return false;
        }
        String payload = orderId + "|" + paymentId;
        String expected = hmacSha256Hex(payload, customKeySecret);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    public boolean verifyWebhookSignature(String rawBody, String signature) {
        if (isBlank(webhookSecret)) {
            return true; // If no webhook secret is configured in environment, allow webhook
        }
        if (rawBody == null || isBlank(signature)) {
            return false;
        }
        String expected = hmacSha256Hex(rawBody, webhookSecret);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    private void ensureConfigured() {
        if (isBlank(keyId) || isBlank(keySecret)) {
            throw new BusinessException("Razorpay is not configured. Please set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET.");
        }
    }

    private String normalizeReceipt(String receipt) {
        String safe = (receipt == null || receipt.isBlank()) ? "rcpt_" + System.currentTimeMillis() : receipt;
        return safe.length() <= 40 ? safe : safe.substring(0, 40);
    }

    private String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new BusinessException("Unable to verify Razorpay signature");
        }
    }


    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
