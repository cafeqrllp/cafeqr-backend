package com.restaurant.pos.sync.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.sync.dto.SyncBootstrapResponse;
import com.restaurant.pos.sync.dto.SyncChangesResponse;
import com.restaurant.pos.sync.dto.SyncPushRequest;
import com.restaurant.pos.sync.dto.SyncPushResponse;
import com.restaurant.pos.sync.service.SyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;

    @GetMapping("/bootstrap")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<SyncBootstrapResponse>> bootstrap() {
        return ResponseEntity.ok(ApiResponse.success(syncService.bootstrap()));
    }

    @GetMapping("/changes")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<SyncChangesResponse>> changes(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false) String syncToken
    ) {
        Instant resolvedSince = since;
        if (syncToken != null && !syncToken.isBlank()) {
            resolvedSince = syncService.decodeSyncToken(syncToken);
        }
        return ResponseEntity.ok(ApiResponse.success(syncService.changes(resolvedSince)));
    }

    @PostMapping("/push")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<SyncPushResponse>> push(@RequestBody SyncPushRequest request) {
        return ResponseEntity.ok(ApiResponse.success(syncService.push(request)));
    }

    /**
     * Re-processes all FAILED_PERMANENT sync operations for the current tenant.
     * Call this after deploying a fix that adds missing dispatch routes (e.g. /settle).
     */
    @PostMapping("/replay-failed")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> replayFailed() {
        return ResponseEntity.ok(ApiResponse.success(syncService.replayFailedOperations()));
    }
}
