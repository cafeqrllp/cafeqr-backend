package com.restaurant.pos.cashdrawer.controller;

import com.restaurant.pos.cashdrawer.domain.CashDrawerEventStatus;
import com.restaurant.pos.cashdrawer.domain.CashDrawerTrigger;
import com.restaurant.pos.cashdrawer.dto.CashDrawerEventDto;
import com.restaurant.pos.cashdrawer.dto.CashDrawerUserSummaryDto;
import com.restaurant.pos.cashdrawer.dto.RecordCashDrawerEventRequest;
import com.restaurant.pos.cashdrawer.service.CashDrawerService;
import com.restaurant.pos.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Cash drawer audit trail. The kick itself is sent by the POS device to its receipt printer;
 * every attempt (sent or failed) is recorded here. Permissions are enforced in {@link CashDrawerService}.
 */
@RestController
@RequestMapping("/api/v1/cash-drawer")
@RequiredArgsConstructor
public class CashDrawerController {

    private final CashDrawerService service;

    @PostMapping("/events")
    public ResponseEntity<ApiResponse<CashDrawerEventDto>> record(@Valid @RequestBody RecordCashDrawerEventRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.record(request)));
    }

    @GetMapping("/events")
    public ResponseEntity<ApiResponse<Page<CashDrawerEventDto>>> search(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID terminalId,
            @RequestParam(required = false) CashDrawerTrigger triggerType,
            @RequestParam(required = false) CashDrawerEventStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                service.search(from, to, orgId, userId, terminalId, triggerType, status, page, size)));
    }

    @GetMapping("/events/summary")
    public ResponseEntity<ApiResponse<List<CashDrawerUserSummaryDto>>> summary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID terminalId) {
        return ResponseEntity.ok(ApiResponse.success(service.summary(from, to, orgId, terminalId)));
    }
}
