package com.restaurant.pos.qrmenu.guest;

import com.restaurant.pos.auth.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Issues the short-lived {@code qr:guest} token handed to the QR menu page together with the table session.
 * <p>
 * The token identifies one guest chat session at one table of one café/branch. cafe-qr-ai accepts it only on
 * its {@code /api/guest/**} endpoints; this backend never accepts it as a login (JwtAuthenticationFilter).
 * It grants nothing the public QR menu does not already allow: orders still go through the public order
 * endpoint and its own validation.
 */
@Service
@RequiredArgsConstructor
public class QrGuestTokenService {

    private final JwtService jwtService;

    @Value("${qr.guest-token.expiration-ms:14400000}") // 4 hours, one dining visit
    private long expirationMs;

    public record GuestToken(String token, String sessionId, Instant expiresAt) {
    }

    public GuestToken issue(UUID clientId, UUID orgId, UUID tableId, String tableNumber) {
        if (clientId == null || tableId == null) {
            throw new IllegalArgumentException("clientId and tableId are required for a guest token");
        }
        String sessionId = UUID.randomUUID().toString();
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtService.SCOPE_CLAIM, JwtService.QR_GUEST_SCOPE);
        claims.put("clientId", clientId.toString());
        if (orgId != null) {
            claims.put("orgId", orgId.toString());
        }
        claims.put("tableId", tableId.toString());
        if (tableNumber != null) {
            claims.put("tableNumber", tableNumber);
        }
        String token = jwtService.generateScopedToken(JwtService.QR_GUEST_SUBJECT_PREFIX + sessionId, claims, expirationMs);
        return new GuestToken(token, sessionId, Instant.now().plusMillis(expirationMs));
    }
}
