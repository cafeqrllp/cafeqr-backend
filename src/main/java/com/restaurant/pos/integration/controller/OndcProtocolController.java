package com.restaurant.pos.integration.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller to handle Open Network for Digital Commerce (ONDC) BPP Protocol requests.
 * In ONDC protocol, all incoming BAP actions (search, select, init, confirm, status, etc.)
 * require an immediate synchronous HTTP 200 ACK response: {"message": {"ack": {"status": "ACK"}}}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ondc")
@RequiredArgsConstructor
public class OndcProtocolController {

    private final ObjectMapper objectMapper;
    private final com.restaurant.pos.integration.ondc.service.OndcProtocolService ondcProtocolService;

    private static final Map<String, Object> ACK_RESPONSE = Map.of(
            "message", Map.of(
                    "ack", Map.of(
                            "status", "ACK"
                    )
            )
    );

    @PostMapping("/{action}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> handleOndcAction(
            @PathVariable("action") String action,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) String requestBody) {
        
        log.info("[ONDC Protocol] Inbound BAP request received for action: {}", action);
        log.debug("[ONDC Protocol] Auth Header: {}", authHeader);
        log.debug("[ONDC Protocol] Payload: {}", requestBody);

        try {
            if (requestBody != null && !requestBody.isBlank()) {
                Map<String, Object> payloadMap = objectMapper.readValue(requestBody, Map.class);
                Object contextObj = payloadMap.get("context");
                if (contextObj instanceof Map<?, ?> context) {
                    log.info("[ONDC Protocol] action={}, transactionId={}, messageId={}, bapUri={}",
                            context.get("action"),
                            context.get("transaction_id"),
                            context.get("message_id"),
                            context.get("bap_uri"));
                }
                // Trigger sub-second automated async callback (on_select, on_init, on_confirm)
                ondcProtocolService.processActionAsync(action, payloadMap);
            }
        } catch (Exception e) {
            log.warn("[ONDC Protocol] Could not parse inbound context: {}", e.getMessage());
        }

        // Return standard Beckn synchronous ACK immediately
        return ResponseEntity.ok(ACK_RESPONSE);
    }
}
