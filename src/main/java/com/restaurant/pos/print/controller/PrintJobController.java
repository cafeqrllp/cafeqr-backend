package com.restaurant.pos.print.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.print.domain.PrintJob;
import com.restaurant.pos.print.dto.CreatePrintJobRequest;
import com.restaurant.pos.print.dto.PrintJobStatusRequest;
import com.restaurant.pos.print.service.PrintJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/print-jobs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
public class PrintJobController {

    private final PrintJobService printJobService;

    @GetMapping("/recent")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRecent() {
        List<PrintJob> jobs = printJobService.recent();
        List<Map<String, Object>> mapped = jobs.stream()
                .map(printJobService::describe)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(mapped));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody CreatePrintJobRequest request) {
        PrintJob job = printJobService.enqueueOrderJob(request.getOrderId(), request.getJobKind());
        return ResponseEntity.ok(ApiResponse.success(printJobService.describe(job)));
    }

    @PostMapping("/orders/{orderId}/{jobKind}/printed")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> markPrintedForOrder(
            @PathVariable UUID orderId,
            @PathVariable String jobKind) {
        List<PrintJob> jobs = printJobService.markPrintedForOrder(orderId, jobKind);
        List<Map<String, Object>> mapped = jobs.stream()
                .map(printJobService::describe)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(mapped));
    }

    @PostMapping("/{id}/printed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markPrinted(@PathVariable UUID id) {
        PrintJob job = printJobService.markPrinted(id);
        return ResponseEntity.ok(ApiResponse.success(printJobService.describe(job)));
    }

    @GetMapping("/pulse")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pulse(@RequestParam(defaultValue = "0") long version) {
        UUID clientId = com.restaurant.pos.common.tenant.TenantContext.getCurrentTenant();
        if (clientId == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }
        long currentVersion = printJobService.getVersion(clientId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "hasNew", currentVersion > version,
                "version", currentVersion
        )));
    }

    @PostMapping("/claim")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> claim(@RequestParam(defaultValue = "3") int limit) {
        List<PrintJob> jobs = printJobService.claimJobs(limit);
        List<Map<String, Object>> mapped = jobs.stream()
                .map(printJobService::describe)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(mapped));
    }

    @PostMapping("/{id}/failed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markFailed(
            @PathVariable UUID id,
            @RequestBody PrintJobStatusRequest request) {
        PrintJob job = printJobService.markFailed(id, request.getMessage());
        return ResponseEntity.ok(ApiResponse.success(printJobService.describe(job)));
    }
}
