package com.restaurant.pos.pos.sale.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

/**
 * Deterministic keyset pagination cursor for POS products.
 * Combines product name and unique ID for tie-breaking with HMAC-SHA256 signature verification.
 */
@Slf4j
public record ProductCursor(String name, UUID id) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HMAC_ALGO = "HmacSHA256";
    // Internal signing secret for cursor integrity protection
    private static final byte[] SIGNING_KEY = "pos-cursor-secret-salt-v2-secure".getBytes(StandardCharsets.UTF_8);

    public String encode() {
        try {
            String json = MAPPER.writeValueAsString(this);
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            String signature = sign(payload);
            return payload + "." + signature;
        } catch (Exception e) {
            log.error("Failed to encode product cursor", e);
            return null;
        }
    }

    public static ProductCursor decode(String cursorStr) {
        if (cursorStr == null || cursorStr.isBlank()) {
            return null;
        }
        try {
            String payload;
            if (cursorStr.contains(".")) {
                int dotIndex = cursorStr.lastIndexOf('.');
                payload = cursorStr.substring(0, dotIndex);
                String providedSig = cursorStr.substring(dotIndex + 1);
                String expectedSig = sign(payload);
                if (!MessageDigest.isEqual(providedSig.getBytes(StandardCharsets.UTF_8), expectedSig.getBytes(StandardCharsets.UTF_8))) {
                    log.warn("Tampered or invalid cursor signature rejected: {}", cursorStr);
                    return null;
                }
            } else {
                // Defensive backwards-compatible fallback for un-signed cursors during transition
                payload = cursorStr;
            }

            byte[] bytes = Base64.getUrlDecoder().decode(payload);
            return MAPPER.readValue(new String(bytes, StandardCharsets.UTF_8), ProductCursor.class);
        } catch (Exception e) {
            log.warn("Invalid product cursor passed: {}", cursorStr);
            return null;
        }
    }

    public static ProductCursor of(com.restaurant.pos.pos.sale.query.PosProductSummaryView product) {
        if (product == null) return null;
        return new ProductCursor(product.getName(), product.getId());
    }

    private static String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(SIGNING_KEY, HMAC_ALGO));
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute cursor signature", e);
        }
    }
}

