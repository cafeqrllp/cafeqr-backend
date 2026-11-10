package com.restaurant.pos.ai.controller;

import com.restaurant.pos.auth.domain.User;
import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.auth.service.JwtService;
import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Issues short-lived, read-only tokens for the CafeQR AI assistant (built-in chat and MCP server).
 * The token carries the same tenant claims as the login token plus {@code scope=ai:read};
 * {@link com.restaurant.pos.auth.config.JwtAuthenticationFilter} rejects every non-GET request made with it.
 */
@RestController
@RequiredArgsConstructor
public class AiTokenController {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final SystemConfigurationService configurationService;

    @Value("${jwt.ai-expiration:1800000}") // 30 minutes
    private long aiTokenExpirationMs;

    @PostMapping("/api/v1/ai/token")
    @PreAuthorize("hasRole('SUPER_ADMIN')") // assistant is limited to super admins for now
    public ResponseEntity<ApiResponse<Map<String, Object>>> issueAiToken(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Keep the branch the caller is currently working in (from their login token), falling back to the user's default.
        UUID orgId = TenantContext.getCurrentOrg() != null ? TenantContext.getCurrentOrg() : user.getOrgId();

        // Settings > "Enable MCP" must be on for this café/branch.
        if (!configurationService.getConfigurationForClientAndBranch(user.getClientId(), orgId).isMcpEnabled()) {
            return ResponseEntity.status(403).body(ApiResponse.error(
                    "Ask CafeQR (MCP) is not available yet."));
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("clientId", user.getClientId());
        claims.put("orgId", orgId);
        claims.put("terminalId", user.getTerminalId());
        claims.put("userId", user.getId());
        claims.put("role", user.getRoleEntity() != null ? user.getRoleEntity().getName() : null);
        claims.put(JwtService.SCOPE_CLAIM, JwtService.AI_READ_SCOPE);

        String token = jwtService.generateToken(claims, user, aiTokenExpirationMs);
        Map<String, Object> body = new HashMap<>();
        body.put("token", token);
        body.put("scope", JwtService.AI_READ_SCOPE);
        body.put("expiresAt", Instant.now().plusMillis(aiTokenExpirationMs).toString());
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    /** Public key used to verify CafeQR tokens (safe to publish; it cannot sign tokens). */
    @GetMapping("/api/v1/public/ai/jwt-public-key")
    public ResponseEntity<ApiResponse<Map<String, String>>> publicKey() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "algorithm", "RS256",
                "format", "X.509",
                "publicKey", jwtService.getPublicKeyBase64())));
    }
}
