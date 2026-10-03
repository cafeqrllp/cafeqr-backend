package com.restaurant.pos.qrmenu.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.qrmenu.command.QrOrderCommandService;
import com.restaurant.pos.qrmenu.query.QrOrderQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Public (unauthenticated) Command Controller for QR Code Ordering — the "Write Side".
 * <p>
 * Handles order placement (new orders and appending to existing table sessions).
 * Delegates to {@link QrOrderCommandService} which orchestrates:
 * inventory deduction, loyalty events, printing, and table status updates.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/public/menu")
@RequiredArgsConstructor
public class QrMenuCommandController {

    private final QrOrderCommandService commandService;
    private final QrOrderQueryService queryService;

    /**
     * POST /api/v1/public/menu/{clientId}/{orgId}/order
     * Places or appends an order from the QR menu (via UUID or slug).
     */
    @PostMapping("/{clientId}/{orgId}/order")
    public ResponseEntity<ApiResponse<Map<String, Object>>> placeOrder(
            @PathVariable String clientId,
            @PathVariable String orgId,
            @RequestBody Map<String, Object> payload) {
        UUID clientUuid = queryService.resolveClientId(clientId);
        UUID orgUuid = queryService.resolveOrgId(clientUuid, orgId);
        Map<String, Object> response = commandService.processOrder(clientUuid, orgUuid, payload);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
