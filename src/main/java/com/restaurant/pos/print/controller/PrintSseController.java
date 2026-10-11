package com.restaurant.pos.print.controller;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.print.service.PrintSseService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/print-jobs")
@RequiredArgsConstructor
public class PrintSseController {

    private final PrintSseService printSseService;

    /**
     * Real-time SSE stream for cloud print stations.
     * Pushes "NEW_JOB" wake-up notifications to connected printers immediately.
     * Completely memory-based; acquires 0 database connections.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<SseEmitter> stream(HttpServletResponse response) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        if (clientId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Disable proxy buffering for instant delivery (Caddy / Cloudflare / Nginx)
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        headers.set("X-Accel-Buffering", "no");

        SseEmitter emitter = printSseService.subscribe(clientId, orgId);
        return new ResponseEntity<>(emitter, headers, HttpStatus.OK);
    }
}
