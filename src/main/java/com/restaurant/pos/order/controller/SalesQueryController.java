package com.restaurant.pos.order.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.order.dto.report.SalesDashboardQuery;
import com.restaurant.pos.order.dto.report.SalesDashboardResponse;
import com.restaurant.pos.order.service.SalesQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v2/sales")
@RequiredArgsConstructor
public class SalesQueryController {

    private final SalesQueryService salesQueryService;

    @PostMapping(
            value = "/dashboard",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ApiResponse<SalesDashboardResponse> getDashboard(
            @Valid @RequestBody SalesDashboardQuery query) {
        return ApiResponse.success(salesQueryService.getDashboard(query));
    }
}
