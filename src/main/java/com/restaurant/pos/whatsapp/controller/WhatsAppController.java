package com.restaurant.pos.whatsapp.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.whatsapp.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/whatsapp")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF') or hasAuthority('ORDER_SETTLE')")
public class WhatsAppController {

    private final WhatsAppService whatsAppService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus(
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        return ResponseEntity.ok(ApiResponse.success(whatsAppService.getGatewayStatus(resolveSessionId(sessionId))));
    }

    @GetMapping("/qr")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getQr(
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        return ResponseEntity.ok(ApiResponse.success(whatsAppService.getGatewayQr(resolveSessionId(sessionId))));
    }

    @PostMapping("/disconnect")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disconnect(
            @RequestBody(required = false) Map<String, String> body) {
        String explicitSessionId = body != null ? body.get("sessionId") : null;
        return ResponseEntity.ok(ApiResponse.success(whatsAppService.disconnectGateway(resolveSessionId(explicitSessionId))));
    }

    @PostMapping("/test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendTest(
            @RequestBody Map<String, String> body) {
        String phone = body != null ? body.get("phone") : null;
        String explicitSessionId = body != null ? body.get("sessionId") : null;
        String resolvedSession = resolveSessionId(explicitSessionId);
        log.info("[WhatsAppController] Received sendTest request for phone: {} (session: {})", phone, resolvedSession);
        return ResponseEntity.ok(ApiResponse.success(whatsAppService.sendTestMessage(resolvedSession, phone)));
    }

    @PostMapping("/clear-contact-session")
    public ResponseEntity<ApiResponse<Map<String, Object>>> clearContactSession(
            @RequestBody Map<String, String> body) {
        String phone = body != null ? body.get("phone") : null;
        String explicitSessionId = body != null ? body.get("sessionId") : null;
        String resolvedSession = resolveSessionId(explicitSessionId);
        log.info("[WhatsAppController] Received clearContactSession request for phone: {} (session: {})", phone, resolvedSession);
        return ResponseEntity.ok(ApiResponse.success(whatsAppService.clearContactSession(resolvedSession, phone)));
    }

    @PostMapping("/send-order-bill")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendOrderBill(
            @RequestBody Map<String, String> body) {
        String orderIdStr = body != null ? body.get("orderId") : null;
        String phone = body != null ? body.get("phone") : null;
        String pdfBase64 = body != null ? body.get("pdfBase64") : null;
        String frontendUrl = body != null ? body.get("frontendUrl") : null;

        log.info("[WhatsAppController] Received sendOrderBill: orderId={}, phone={}, hasPdfBase64={}, frontendUrl={}",
                orderIdStr, phone, pdfBase64 != null && !pdfBase64.isBlank(), frontendUrl);

        if (orderIdStr == null || orderIdStr.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("orderId is required"));
        }

        UUID orderId;
        try {
            orderId = UUID.fromString(orderIdStr.trim());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid orderId format"));
        }

        try {
            boolean sent = whatsAppService.sendOrderBillWithAttachment(orderId, phone, pdfBase64, frontendUrl);
            log.info("[WhatsAppController] sendOrderBill result for order {}: sent={}", orderId, sent);
            Map<String, Object> result = Map.of(
                    "success", sent,
                    "orderId", orderId,
                    "message", sent ? "WhatsApp bill dispatched successfully" : "Failed to dispatch WhatsApp bill"
            );
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception ex) {
            log.error("[WhatsAppController] Failed to dispatch WhatsApp bill for order {}: {}", orderId, ex.getMessage(), ex);
            return ResponseEntity.status(500).body(ApiResponse.error("Failed to dispatch WhatsApp bill: " + ex.getMessage()));
        }
    }

    private String resolveSessionId(String explicitSessionId) {
        if (explicitSessionId != null && !explicitSessionId.isBlank()) {
            return explicitSessionId.trim();
        }
        UUID orgId = TenantContext.getCurrentOrg();
        if (orgId != null) {
            return "org_" + orgId;
        }
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId != null) {
            return "client_" + tenantId;
        }
        return "default";
    }
}
