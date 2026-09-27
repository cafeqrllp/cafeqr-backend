package com.restaurant.pos.purchase.api;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.security.StaffAccess;
import com.restaurant.pos.order.dto.OrderResponseDto;
import com.restaurant.pos.purchase.dto.PurchaseOrderSummaryDto;
import com.restaurant.pos.purchase.query.PurchaseOrderQueryService;
import com.restaurant.pos.purchase.query.PurchaseOrderSearchRequest;
import com.restaurant.pos.subscription.annotation.RequireModule;
import com.restaurant.pos.subscription.domain.ModuleName;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * CQRS Query Controller for Purchase Orders.
 * Handles read operations (SEARCH, LIST DRAFTS, GET BY ID).
 * Lives in feature-based package 'purchase.api'.
 */
@Slf4j
@StaffAccess
@RestController
@RequestMapping("/api/v1/purchase/orders")
@RequiredArgsConstructor
@Validated
@RequireModule(ModuleName.INVENTORY)
@Tag(name = "Purchase Order Queries", description = "Endpoints for querying and reading Purchase Orders.")
public class PurchaseOrderQueryController {

    private final PurchaseOrderQueryService queryService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Search Purchase Orders", description = "Paginated filtering using encapsulated search request model and Spring Pageable.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved Purchase Orders"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid search criteria"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content)
    })
    public ResponseEntity<ApiResponse<Page<PurchaseOrderSummaryDto>>> searchPurchaseOrders(
            @Valid PurchaseOrderSearchRequest searchRequest,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        log.info("Searching Purchase Orders | vendorId={} | warehouseId={} | status={} | page={}",
                searchRequest.getVendorId(), searchRequest.getWarehouseId(), searchRequest.getStatus(), pageable.getPageNumber());

        Page<PurchaseOrderSummaryDto> dtos = queryService.searchPurchaseOrders(searchRequest, pageable);
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/drafts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Get Purchase Order Drafts", description = "Retrieves all active draft Purchase Orders.")
    public ResponseEntity<ApiResponse<List<OrderResponseDto>>> getDraftPurchaseOrders() {
        log.info("Fetching draft Purchase Orders");
        List<OrderResponseDto> drafts = queryService.getDraftPurchaseOrders();
        return ResponseEntity.ok(ApiResponse.success(drafts));
    }

    @GetMapping("/reports/summary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Get Purchase Report Summary", description = "Retrieves executive financial KPIs and order counts for purchases within date range.")
    public ResponseEntity<ApiResponse<com.restaurant.pos.purchase.dto.PurchaseReportSummaryDto>> getPurchaseReportSummary(
            @RequestParam(required = false) java.time.Instant from,
            @RequestParam(required = false) java.time.Instant to,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) UUID warehouseId) {
        log.info("Fetching Purchase Report Summary | from={} | to={} | orgId={} | vendorId={}", from, to, orgId, vendorId);
        return ResponseEntity.ok(ApiResponse.success(queryService.getPurchaseSummary(from, to, orgId, vendorId, warehouseId)));
    }

    @GetMapping("/reports/item-wise")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Get Purchase Item Report", description = "Retrieves item-wise aggregated procurement metrics.")
    public ResponseEntity<ApiResponse<List<com.restaurant.pos.purchase.dto.PurchaseItemReportDto>>> getPurchaseItemReport(
            @RequestParam(required = false) java.time.Instant from,
            @RequestParam(required = false) java.time.Instant to,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) UUID warehouseId) {
        log.info("Fetching Purchase Item Report | from={} | to={}", from, to);
        return ResponseEntity.ok(ApiResponse.success(queryService.getPurchaseItemReport(from, to, orgId, vendorId, warehouseId)));
    }

    @GetMapping("/reports/vendor-wise")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Get Purchase Vendor Report", description = "Retrieves vendor spend and ledger breakdown.")
    public ResponseEntity<ApiResponse<List<com.restaurant.pos.purchase.dto.PurchaseVendorReportDto>>> getPurchaseVendorReport(
            @RequestParam(required = false) java.time.Instant from,
            @RequestParam(required = false) java.time.Instant to,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) UUID warehouseId) {
        log.info("Fetching Purchase Vendor Report | from={} | to={}", from, to);
        return ResponseEntity.ok(ApiResponse.success(queryService.getPurchaseVendorReport(from, to, orgId, vendorId, warehouseId)));
    }

    @GetMapping("/reports/payment-breakdown")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Get Purchase Payment Breakdown", description = "Retrieves breakdown of purchase outflows by payment method.")
    public ResponseEntity<ApiResponse<List<com.restaurant.pos.order.dto.report.PaymentBreakdownDto>>> getPurchasePaymentBreakdown(
            @RequestParam(required = false) java.time.Instant from,
            @RequestParam(required = false) java.time.Instant to,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) UUID warehouseId) {
        log.info("Fetching Purchase Payment Breakdown | from={} | to={}", from, to);
        return ResponseEntity.ok(ApiResponse.success(queryService.getPurchasePaymentBreakdown(from, to, orgId, vendorId, warehouseId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Get Purchase Order by ID", description = "Returns a single Purchase Order with detailed line items.")
    public ResponseEntity<ApiResponse<OrderResponseDto>> getPurchaseOrder(
            @Parameter(description = "UUID of the Purchase Order", required = true) @PathVariable UUID id) {
        log.info("Fetching Purchase Order by ID | id={}", id);
        OrderResponseDto dto = queryService.getPurchaseOrder(id);
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    @GetMapping("/{id}/revisions")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Get Purchase Order Revision History", description = "Returns revision history for a purchase order (current + VOID predecessors).")
    public ResponseEntity<ApiResponse<List<OrderResponseDto>>> getPurchaseOrderRevisions(
            @Parameter(description = "UUID of the Purchase Order", required = true) @PathVariable UUID id) {
        log.info("Fetching Purchase Order revisions | id={}", id);
        List<OrderResponseDto> revisions = queryService.getPurchaseOrderRevisions(id);
        return ResponseEntity.ok(ApiResponse.success(revisions));
    }

    @GetMapping("/{id}/payment-splits")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Get Purchase Order Payment Splits", description = "Returns payment splits for a settled mixed payment purchase order.")
    public ResponseEntity<ApiResponse<List<com.restaurant.pos.order.dto.PaymentSplitResponseDto>>> getPaymentSplits(
            @Parameter(description = "UUID of the Purchase Order", required = true) @PathVariable UUID id) {
        log.info("Fetching Purchase Order payment splits | id={}", id);
        List<com.restaurant.pos.order.dto.PaymentSplitResponseDto> splits = queryService.getPaymentSplits(id);
        return ResponseEntity.ok(ApiResponse.success(splits));
    }
}
