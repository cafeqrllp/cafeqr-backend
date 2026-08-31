package com.restaurant.pos.common.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Utility for encrypting and decrypting entity IDs (such as orgId / branchId)
 * to prevent exposing raw UUIDs in public storefront URLs.
 */
public class TokenEncryptionUtil {

    private static final byte[] KEY_BYTES = "CafeQrOrgEnc2026".getBytes(StandardCharsets.UTF_8);

    public static String encryptOrgId(UUID orgId) {
        if (orgId == null) return null;
        try {
            return "enc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(orgId.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return orgId.toString();
        }
    }

    public static UUID decryptOrgId(String token) {
        if (token == null || token.isBlank() || "null".equalsIgnoreCase(token)) {
            return null;
        }
        String clean = token.trim();
        try {
            return UUID.fromString(clean);
        } catch (Exception ignored) {
            // Not a direct UUID
        }

        if (clean.startsWith("enc_")) {
            try {
                byte[] decoded = Base64.getUrlDecoder().decode(clean.substring(4));
                String raw = new String(decoded, StandardCharsets.UTF_8);
                return UUID.fromString(raw.trim());
            } catch (Exception ignored) {
            }
        }

        try {
            byte[] decoded = Base64.getUrlDecoder().decode(clean);
            SecretKeySpec secretKey = new SecretKeySpec(KEY_BYTES, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] decrypted = cipher.doFinal(decoded);
            String decryptedStr = new String(decrypted, StandardCharsets.UTF_8);
            return UUID.fromString(decryptedStr.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
