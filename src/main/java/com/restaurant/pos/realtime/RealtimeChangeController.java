package com.restaurant.pos.realtime;

import com.restaurant.pos.qrmenu.query.QrOrderQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Public (unauthenticated) change-signal stream for the QR menu. Sends only "changed" pings;
 * clients then re-fetch via the existing public menu endpoints.
 */
@RestController
@RequestMapping("/api/v1/public/menu")
@RequiredArgsConstructor
public class RealtimeChangeController {

    private final RealtimeChangeService realtimeChangeService;
    private final QrOrderQueryService queryService;

    @GetMapping(value = "/{clientId}/{orgId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @PathVariable String clientId,
            @PathVariable String orgId,
            @RequestParam(required = false) String tableId) {
        UUID clientUuid = queryService.resolveClientId(clientId);
        if (clientUuid == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        UUID orgUuid = queryService.resolveOrgId(clientUuid, orgId);
        boolean orgGiven = orgId != null && !orgId.isBlank() && !"null".equalsIgnoreCase(orgId);
        if (orgGiven && orgUuid == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build(); // unknown branch, never a wildcard stream
        }
        if (orgUuid == null && tableId != null && !tableId.isBlank()) {
            orgUuid = queryService.resolveTableOrgId(clientUuid, tableId); // legacy QR codes without a branch
        }

        SseEmitter emitter = realtimeChangeService.subscribe(clientUuid, orgUuid);
        if (emitter == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "30").build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        headers.set("X-Accel-Buffering", "no");
        return new ResponseEntity<>(emitter, headers, HttpStatus.OK);
    }
}
