package com.restaurant.pos.pos.sale.api;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.security.StaffAccess;
import com.restaurant.pos.order.dto.CreateOrderRequest;
import com.restaurant.pos.order.dto.OrderResponseDto;
import com.restaurant.pos.pos.sale.command.PosSaleCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

/**
 * Write-side POS Sales endpoint — the "Command Side" of in-process CQRS.
 * <p>
 * Exposes {@code POST /api/v1/pos/sale/orders} for creating sale orders.
 * Uses the exact same transactional pipeline as the existing
 * {@code POST /api/v1/orders} endpoint (inventory, invoicing, payments,
 * accounting, loyalty) through delegation.
 * </p>
 */
@Slf4j
@StaffAccess
@RestController
@RequestMapping("/api/v1/pos/sale")
@RequiredArgsConstructor
@Validated
@Tag(name = "POS Sale (V2)", description = "High-performance POS sale command endpoints.")
public class PosSaleCommandController {

    private final PosSaleCommandService commandService;

    /**
     * Creates a new sale order.
     * <p>
     * This endpoint accepts the same {@link CreateOrderRequest} payload as the
     * existing {@code POST /api/v1/orders} endpoint so the frontend can switch
     * to this endpoint with zero payload changes.
     * </p>
     */
    @PostMapping("/orders")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF') or hasAuthority('ORDER_WRITE')")
    @Operation(summary = "Create sale order (V2)", description = "Creates a new sale order via the optimized POS pipeline. Accepts the same payload as POST /api/v1/orders.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Sale order created successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Idempotent match — existing order returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request payload"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<OrderResponseDto>> createSaleOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Parameter(description = "Sale order creation payload", required = true)
            @Valid @RequestBody CreateOrderRequest request) {

        PosSaleCommandService.CreateSaleResult result =
                commandService.createSaleOrder(request, idempotencyKey);

        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(result.order()));
    }

}
