package com.restaurant.pos.order.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.security.StaffAccess;
import com.restaurant.pos.order.dto.report.OrderOverviewQuery;
import com.restaurant.pos.order.dto.report.OrderOverviewResponse;
import com.restaurant.pos.order.dto.report.OrderOverviewRowDto;
import com.restaurant.pos.order.dto.report.PageResponse;
import com.restaurant.pos.order.service.OrderOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@StaffAccess
@RestController
@RequestMapping("/api/v1/orders/overview")
@RequiredArgsConstructor
@Tag(name = "Business Overview", description = "Server-side aggregated analytics for the Overview (dashboard) screen.")
public class OrderOverviewController {

    private final OrderOverviewService overviewService;

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Overview KPIs and breakdowns",
            description = "Totals, status split, fulfillment/payment or warehouse/vendor split, hourly and trend bins, and top products for a date range — aggregated in SQL.")
    public ResponseEntity<ApiResponse<OrderOverviewResponse>> summary(@Valid @ModelAttribute OrderOverviewQuery query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(15, TimeUnit.SECONDS).cachePrivate())
                .body(ApiResponse.success(overviewService.getSummary(query)));
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Overview order list (paged)",
            description = "Lightweight, server-sorted and paginated rows for the Overview table view.")
    public ResponseEntity<ApiResponse<PageResponse<OrderOverviewRowDto>>> orders(@Valid @ModelAttribute OrderOverviewQuery query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(15, TimeUnit.SECONDS).cachePrivate())
                .body(ApiResponse.success(overviewService.getOrders(query)));
    }
}
