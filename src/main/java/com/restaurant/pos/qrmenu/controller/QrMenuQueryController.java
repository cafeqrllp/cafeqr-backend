package com.restaurant.pos.qrmenu.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.qrmenu.query.QrOrderQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public (unauthenticated) Query Controller for QR Code Menu — the "Read Side".
 * <p>
 * Handles all read-only operations:
 * <ul>
 *   <li>Menu retrieval</li>
 *   <li>Table session info</li>
 *   <li>Default org lookup</li>
 * </ul>
 * Supports both raw UUIDs and human-readable slugs.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/public/menu")
@RequiredArgsConstructor
public class QrMenuQueryController {

    private final QrOrderQueryService queryService;

    /**
     * GET /api/v1/public/menu/{clientId}/{orgId}
     * Returns the full active menu for a given restaurant (client + org, via UUID or slug).
     */
    @GetMapping("/{clientId}/{orgId}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMenu(
            @PathVariable String clientId,
            @PathVariable String orgId) {
        UUID clientUuid = queryService.resolveClientId(clientId);
        UUID orgUuid = queryService.resolveOrgId(clientUuid, orgId);
        List<Map<String, Object>> menu = queryService.getMenu(clientUuid, orgUuid);
        return ResponseEntity.ok(ApiResponse.success(menu));
    }

    /**
     * GET /api/v1/public/menu/{clientId}/{orgId}/table/{tableId}
     * Returns table info & active open session for the scanned QR code (via UUID or slug).
     */
    @GetMapping("/{clientId}/{orgId}/table/{tableId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTableInfo(
            @PathVariable String clientId,
            @PathVariable String orgId,
            @PathVariable String tableId) {
        Map<String, Object> info = queryService.getTableSessionInfo(clientId, orgId, tableId);
        return ResponseEntity.ok(ApiResponse.success(info));
    }

    /**
     * GET /api/v1/public/menu/{clientId}/default-org
     * Helper endpoint for legacy QR code redirects. Returns the default orgId for a given clientId/slug.
     */
    @GetMapping("/{clientId}/default-org")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDefaultOrg(@PathVariable String clientId) {
        Map<String, Object> result = queryService.getDefaultOrg(clientId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
