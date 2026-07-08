package com.restaurant.pos.order.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.security.StaffAccess;
import org.springframework.security.access.prepost.PreAuthorize;
import com.restaurant.pos.common.idempotency.IdempotencyGuard;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.domain.OrderStatus;
import com.restaurant.pos.order.domain.PaymentStatus;
import com.restaurant.pos.order.dto.CreateOrderRequest;
import com.restaurant.pos.order.dto.UpdateOrderRequest;
import com.restaurant.pos.order.dto.OrderResponseDto;
import com.restaurant.pos.order.dto.OrderDtoMapper;
import com.restaurant.pos.order.dto.OrderCancelRequest;
import com.restaurant.pos.order.dto.OrderCreditCompletionRequest;
import com.restaurant.pos.order.dto.OrderMoveTableRequest;
import com.restaurant.pos.order.dto.OrderPrintOptionsRequest;
import com.restaurant.pos.order.dto.OrderSearchCriteria;
import com.restaurant.pos.order.dto.OrderSettleRequest;
import com.restaurant.pos.order.dto.OrderSummaryDto;
import com.restaurant.pos.order.dto.PaymentSplitResponseDto;
import com.restaurant.pos.order.dto.OrderPaymentDto;
import com.restaurant.pos.order.service.OrderService;
import com.restaurant.pos.order.service.OrderRequestFingerprintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@StaffAccess
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Validated
@Tag(name = "Order Management", description = "Endpoints for creating, retrieving, and managing sale, purchase, and expense orders.")
public class OrderController {

    private final OrderService orderService;
    private final OrderDtoMapper orderDtoMapper;
    private final IdempotencyGuard idempotencyGuard;
    private final OrderRequestFingerprintService fingerprintService;

    /**
     * Order statuses that represent financial or terminal states.
     * These must only be reached via dedicated command endpoints
     * (/bill, /settle, /cancel, /complete-credit) — never via the generic
     * /status patch endpoint.
     */
    private static final Set<OrderStatus> FINANCIAL_STATUSES = Set.of(
            OrderStatus.BILLED, OrderStatus.COMPLETED, OrderStatus.CANCELLED, OrderStatus.VOID
    );
    private static final Set<PaymentStatus> FINANCIAL_PAYMENT_STATUSES = Set.of(
            PaymentStatus.PAID, PaymentStatus.VOID
    );

    // -----------------------------------------------------------------
    // Query endpoints
    // -----------------------------------------------------------------

    @GetMapping
    @Operation(summary = "List orders paginated", description = "Returns a paginated list of all orders, optionally filtered by status.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved orders list"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid pagination parameters or size limit exceeded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden access", content = @Content)
    })
    public ResponseEntity<ApiResponse<Page<OrderResponseDto>>> getOrders(
            @Parameter(description = "Optional status filter or comma-separated list of statuses (e.g. 'DRAFT,COMPLETED')", example = "DRAFT") @RequestParam(required = false) String status,
            @Parameter(description = "Zero-indexed page number", example = "0") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (maximum 200)", example = "20") @RequestParam(defaultValue = "20") @Min(1) @Max(value = 200, message = "Page size cannot exceed 200") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Order> entityPage = orderService.getOrders(status, pageable);
        Page<OrderResponseDto> dtoPage = entityPage.map(orderDtoMapper::toResponseDto);
        return ResponseEntity.ok(ApiResponse.success(dtoPage));
    }

    @GetMapping("/type/{type}")
    @Operation(summary = "List orders by type", description = "Returns orders of a specific type: SALE, PURCHASE, or EXPENSE.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved orders of the specified type"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid order type provided"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden access", content = @Content)
    })
    public ResponseEntity<ApiResponse<Page<OrderResponseDto>>> getOrdersByType(
            @Parameter(description = "The type of orders to retrieve (SALE, PURCHASE, EXPENSE)", required = true, example = "SALE") @PathVariable String type,
            @Parameter(description = "Zero-indexed page number", example = "0") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (maximum 200)", example = "20") @RequestParam(defaultValue = "20") @Min(1) @Max(value = 200, message = "Page size cannot exceed 200") int size) {
        OrderType orderType;
        try {
            orderType = OrderType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_ORDER_TYPE", "Invalid order type: " + type));
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Order> orders = orderService.getOrdersByType(orderType, pageable);
        Page<OrderResponseDto> dtos = orders.map(orderDtoMapper::toResponseDto);
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/sales/live")
    @Operation(summary = "Live sales orders", description = "Returns currently open (non-completed) sale orders.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved live sales orders"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden access", content = @Content)
    })
    public ResponseEntity<ApiResponse<List<OrderSummaryDto>>> getLiveSalesOrders() {
        return ResponseEntity.ok(ApiResponse.success(orderService.getLiveSalesOrders()));
    }

    @GetMapping("/history")
    @Operation(summary = "Sale order history", description = "Paginated sale order history with optional date and search filters. Exclusively processes SALE order types.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved sale order history"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request filters or size limit exceeded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden access", content = @Content)
    })
    public ResponseEntity<ApiResponse<Page<OrderSummaryDto>>> getOrderHistory(
            @Parameter(description = "Deprecated order type parameter (always SALE history). Sunset planned.", deprecated = true) @RequestParam(required = false) @Deprecated OrderType type,
            @Parameter(description = "Filter history from date-time (ISO-8601 UTC Instant)", example = "2026-05-24T00:00:00Z") @RequestParam(required = false) Instant fromDate,
            @Parameter(description = "Filter history to date-time (ISO-8601 UTC Instant)", example = "2026-05-24T23:59:59Z") @RequestParam(required = false) Instant toDate,
            @Parameter(description = "Search term to match customer name or phone") @RequestParam(required = false) String q,
            @Parameter(description = "Filter by order status (e.g. 'DRAFT', 'COMPLETED', 'PAID', 'CANCELLED')") @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID terminalId,
            @Parameter(description = "Zero-indexed page number", example = "0") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (maximum 500)", example = "20") @RequestParam(defaultValue = "20") @Min(1) @Max(value = 500, message = "Page size cannot exceed 500") int size) {
        if (type != null && type != OrderType.SALE) {
            log.warn("Legacy type parameter '{}' passed to /history is ignored. This endpoint exclusively retrieves SALE history.", type);
        }
        return ResponseEntity
                .ok(ApiResponse.success(orderService.getSalesOrderHistory(fromDate, toDate, page, size, q, status, orgId, terminalId)));
    }

    @GetMapping("/search")
    @Operation(summary = "Search orders paginated", description = "Filter orders by type, date range, status, branch, vendor, customer, warehouse, or payment method.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully searched and retrieved orders matching criteria"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid search criteria or size limit exceeded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden access", content = @Content)
    })
    public ResponseEntity<ApiResponse<Page<OrderResponseDto>>> searchOrders(
            @Parameter(description = "Filter by order type (SALE, PURCHASE, EXPENSE)") @RequestParam(required = false) OrderType type,
            @Parameter(description = "Start date range filter (ISO-8601 UTC Instant)") @RequestParam(required = false) Instant fromDate,
            @Parameter(description = "End date range filter (ISO-8601 UTC Instant)") @RequestParam(required = false) Instant toDate,
            @Parameter(description = "Filter by exact order status (e.g. 'DRAFT', 'COMPLETED')") @RequestParam(required = false) String status,
            @Parameter(description = "Filter by branch/org ID") @RequestParam(required = false) UUID branchId,
            @Parameter(description = "Filter by supplier/vendor ID") @RequestParam(required = false) UUID vendorId,
            @Parameter(description = "Filter by buyer/customer ID") @RequestParam(required = false) UUID customerId,
            @Parameter(description = "Filter by storage/warehouse ID") @RequestParam(required = false) UUID warehouseId,
            @Parameter(description = "Filter by payment method (CASH, CREDIT, CARD, etc.)") @RequestParam(required = false) String paymentMethod,
            @Parameter(description = "Generic search term for order number or notes") @RequestParam(required = false) String searchTerm,
            @RequestParam(required = false) UUID terminalId,
            @Parameter(description = "Zero-indexed page number", example = "0") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (maximum 10000)", example = "20") @RequestParam(defaultValue = "20") @Min(1) @Max(value = 10000, message = "Page size cannot exceed 10000") int size) {
        OrderSearchCriteria criteria = OrderSearchCriteria.builder()
                .orderType(type)
                .fromDate(fromDate)
                .toDate(toDate)
                .status(status)
                .branchId(branchId)
                .vendorId(vendorId)
                .customerId(customerId)
                .warehouseId(warehouseId)
                .paymentMethod(paymentMethod)
                .searchTerm(searchTerm)
                .terminalId(terminalId)
                .build();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Order> entityPage = orderService.searchOrders(criteria, pageable);
        Page<OrderResponseDto> dtoPage = entityPage.map(orderDtoMapper::toResponseDto);
        return ResponseEntity.ok(ApiResponse.success(dtoPage));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get order by ID", description = "Returns a single order with detailed lines and payment information.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved the order"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<OrderResponseDto>> getOrder(
            @Parameter(description = "Unique UUID of the order", required = true) @PathVariable UUID id) {
        Order order = orderService.getOrder(id);
        return ResponseEntity.ok(ApiResponse.success(orderDtoMapper.toResponseDto(order)));
    }

    @GetMapping("/{id}/revisions")
    @Operation(summary = "Get order revision history", description = "Returns all revisions of an order (current + all prior VOID records), ordered from oldest to newest.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved order revision history"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<List<OrderResponseDto>>> getOrderRevisions(
            @Parameter(description = "Unique UUID of the order", required = true) @PathVariable UUID id) {
        List<OrderResponseDto> revisions = orderService.getOrderRevisions(id)
                .stream().map(orderDtoMapper::toResponseDto).toList();
        return ResponseEntity.ok(ApiResponse.success(revisions));
    }

    @GetMapping("/{id}/payment-splits")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Get payment splits for an order", description = "Returns payment splits for a settled mixed payment order.")
    public ResponseEntity<ApiResponse<List<PaymentSplitResponseDto>>> getPaymentSplits(@PathVariable UUID id) {
        List<PaymentSplitResponseDto> splits = orderService.getPaymentSplits(id)
                .stream()
                .map(ps -> new PaymentSplitResponseDto(ps.getId(), ps.getPaymentMethod(), ps.getAmount(), ps.getReferenceNo()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(splits));
    }

    @GetMapping("/{id}/payments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Get payments for an order", description = "Returns all payments and allocations linked to this order.")
    public ResponseEntity<ApiResponse<List<OrderPaymentDto>>> getOrderPayments(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(orderService.getOrderPayments(id)));
    }

    // -----------------------------------------------------------------
    // Command endpoints — create & update
    // -----------------------------------------------------------------

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF') or hasAuthority('ORDER_WRITE')")
    @Operation(summary = "Create order", description = "Creates a new order. The orderType field determines SALE, PURCHASE, or EXPENSE routing.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Successfully created the order"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request payload or business constraint violation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden access", content = @Content)
    })
    public ResponseEntity<ApiResponse<OrderResponseDto>> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Parameter(description = "Order details including type, branch, items, totals, and customer details", required = true) @Valid @RequestBody CreateOrderRequest request) {
        log.info("Creating order | type={} | warehouseId={} | hasIdempotencyKey={}",
                request.getOrderType(), request.getWarehouseId(), idempotencyKey != null && !idempotencyKey.isBlank());

        if (org.springframework.util.StringUtils.hasText(idempotencyKey)
                && org.springframework.util.StringUtils.hasText(request.getSourceLocalRef())
                && !idempotencyKey.equals(request.getSourceLocalRef())) {
            throw new com.restaurant.pos.common.exception.BusinessException(
                "Idempotency-Key does not match sourceLocalRef"
            );
        }

        String sourceLocalRef = org.springframework.util.StringUtils.hasText(idempotencyKey)
                ? idempotencyKey
                : request.getSourceLocalRef();

        String fingerprint = fingerprintService.fingerprint(request);
        Order mappedEntity = orderDtoMapper.toEntity(request);
        mappedEntity.setRequestFingerprint(fingerprint);
        mappedEntity.setSourceLocalRef(sourceLocalRef);

        com.restaurant.pos.order.dto.IdempotentCreateResult result = orderService.createOrderIdempotently(mappedEntity);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(orderDtoMapper.toResponseDto(result.order())));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF') or hasAuthority('ORDER_WRITE')")
    @Operation(summary = "Update order", description = "Merges changes into the existing order (partial update). Triggers invoice/payment generation on status transitions.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully updated the order"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request payload or modification constraint violation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<OrderResponseDto>> updateOrder(
            @Parameter(description = "UUID of the order to update", required = true) @PathVariable UUID id,
            @Parameter(description = "Order update payload", required = true) @Valid @RequestBody UpdateOrderRequest request) {
        log.info("Updating order | id={} | status={} | paymentStatus={}", id, request.getOrderStatus(),
                request.getPaymentStatus());
        Order updates = orderDtoMapper.toEntity(request);
        Order savedEntity = orderService.updateOrder(id, updates);
        return ResponseEntity.ok(ApiResponse.success(orderDtoMapper.toResponseDto(savedEntity)));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF') or hasAuthority('ORDER_WRITE')")
    @Operation(summary = "Update operational order status", description = "Lightweight status change for operational transitions only (DRAFT→KITCHEN, KITCHEN→READY, etc.). Financial transitions (BILLED, COMPLETED, PAID, VOID, CANCELLED) must use dedicated command endpoints.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully updated the order status"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid or forbidden status transition"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<OrderResponseDto>> updateOrderStatus(
            @Parameter(description = "UUID of the order", required = true) @PathVariable UUID id,
            @Parameter(description = "Next operational status (DRAFT, CONFIRMED, IN_PROGRESS, READY)", required = true, example = "IN_PROGRESS") @RequestParam OrderStatus status,
            @Parameter(description = "Optional payment status update — PENDING or PARTIAL only") @RequestParam(required = false) PaymentStatus paymentStatus,
            @Parameter(description = "Optional operational notes") @RequestParam(required = false) String description) {
        if (FINANCIAL_STATUSES.contains(status)) {
            return ResponseEntity.badRequest().body(ApiResponse.error(
                    "FORBIDDEN_STATUS_TRANSITION",
                    "Status '" + status + "' must be set via a dedicated command endpoint (/bill, /settle, /cancel, /complete-credit)."
            ));
        }
        if (paymentStatus != null && FINANCIAL_PAYMENT_STATUSES.contains(paymentStatus)) {
            return ResponseEntity.badRequest().body(ApiResponse.error(
                    "FORBIDDEN_PAYMENT_STATUS_TRANSITION",
                    "Payment status '" + paymentStatus + "' must be set via a dedicated command endpoint (/settle, /complete-credit)."
            ));
        }
        log.info("Updating order status | id={} | status={} | paymentStatus={}", id, status, paymentStatus);
        Order savedEntity = orderService.updateOrderStatus(id, status, paymentStatus, description);
        return ResponseEntity.ok(ApiResponse.success(orderDtoMapper.toResponseDto(savedEntity)));
    }

    // -----------------------------------------------------------------
    // Financial command endpoints
    // -----------------------------------------------------------------

    @PostMapping("/{id}/bill")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF') or hasAuthority('ORDER_BILL')")
    @Operation(summary = "Bill order", description = "Marks the order as BILLED and generates a customer invoice. Replays identically if a duplicate Idempotency-Key is provided.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully marked order as billed and generated invoice"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Billing constraint violation or already billed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<OrderResponseDto>> billOrder(
            @Parameter(description = "UUID of the order to bill", required = true) @PathVariable UUID id,
            @Parameter(description = "Optional idempotency key to prevent double billing") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) OrderPrintOptionsRequest request) {
        OrderPrintOptionsRequest safeRequest = request == null ? new OrderPrintOptionsRequest() : request;
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            log.warn("Missing Idempotency-Key header for bill order | id={}", id);
        }
        log.info("Billing order | id={}", id);
        OrderResponseDto responseDto = idempotencyGuard.execute(
                "bill",
                id,
                idempotencyKey,
                OrderResponseDto.class,
                () -> orderDtoMapper.toResponseDto(orderService.billOrder(id, safeRequest.getSkipAutoPrintKinds()))
        );
        return ResponseEntity.ok(ApiResponse.success(responseDto));
    }

    @PostMapping("/{id}/settle")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF') or hasAuthority('ORDER_SETTLE')")
    @Operation(summary = "Settle order", description = "Marks the order as PAID and records a payment. Replays identically if a duplicate Idempotency-Key is provided.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully settled the order and recorded payment"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Settlement constraints validation failed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<OrderResponseDto>> settleOrder(
            @Parameter(description = "UUID of the order to settle", required = true) @PathVariable UUID id,
            @Parameter(description = "Optional idempotency key to prevent double payment") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Parameter(description = "Settlement parameters including payment method and amounts") @Valid @RequestBody OrderSettleRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            log.warn("Missing Idempotency-Key header for settle order | id={}", id);
        }
        log.info("Settling order | id={} | method={}", id, request.getPaymentMethod() != null ? request.getPaymentMethod() : "default");
        OrderResponseDto responseDto = idempotencyGuard.execute(
                "settle",
                id,
                idempotencyKey,
                OrderResponseDto.class,
                () -> orderDtoMapper.toResponseDto(orderService.settleOrder(id, request))
        );
        return ResponseEntity.ok(ApiResponse.success(responseDto));
    }

    @PostMapping("/{id}/complete-credit")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Complete credit order", description = "Finalises a credit order by recording the credit customer and generating the invoice. Replays identically if a duplicate Idempotency-Key is provided.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully completed the credit order"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Credit completion validation failed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<OrderResponseDto>> completeCreditOrder(
            @Parameter(description = "UUID of the credit order to finalise", required = true) @PathVariable UUID id,
            @Parameter(description = "Optional idempotency key to prevent duplicate credit completion") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) OrderCreditCompletionRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            log.warn("Missing Idempotency-Key header for complete-credit order | id={}", id);
        }
        log.info("Completing credit order | id={}", id);
        OrderResponseDto responseDto = idempotencyGuard.execute(
                "complete-credit",
                id,
                idempotencyKey,
                OrderResponseDto.class,
                () -> orderDtoMapper.toResponseDto(orderService.completeCreditOrder(id, request))
        );
        return ResponseEntity.ok(ApiResponse.success(responseDto));
    }

    @PostMapping("/{id}/move-table")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF') or hasAuthority('ORDER_WRITE')")
    @Operation(summary = "Move table", description = "Transfers this order to a different table.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully transferred the order to the new table"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Target table is not available or invalid"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<OrderResponseDto>> moveTable(
            @Parameter(description = "UUID of the order to move", required = true) @PathVariable UUID id,
            @Parameter(description = "Target table details", required = true) @Valid @RequestBody OrderMoveTableRequest request) {
        log.info("Moving table | orderId={} | toTable={}", id, request.getTableId());
        Order savedEntity = orderService.moveTable(id, request);
        return ResponseEntity.ok(ApiResponse.success(orderDtoMapper.toResponseDto(savedEntity)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN') or hasAuthority('ORDER_CANCEL')")
    @Operation(summary = "Cancel order", description = "Cancels the order and voids associated invoices. Replays identically if a duplicate Idempotency-Key is provided.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully cancelled the order"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Cancellation validation failed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden access", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<OrderResponseDto>> cancelOrder(
            @Parameter(description = "UUID of the order to cancel", required = true) @PathVariable UUID id,
            @Parameter(description = "Optional idempotency key to prevent duplicate cancel processing") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Parameter(description = "Cancellation reason details") @Valid @RequestBody(required = false) OrderCancelRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            log.warn("Missing Idempotency-Key header for cancel order | id={}", id);
        }
        log.info("Cancelling order | id={} | reason={}", id, request != null ? request.getReason() : "none");
        OrderResponseDto responseDto = idempotencyGuard.execute(
                "cancel",
                id,
                idempotencyKey,
                OrderResponseDto.class,
                () -> orderDtoMapper.toResponseDto(orderService.cancelOrder(id, request))
        );
        return ResponseEntity.ok(ApiResponse.success(responseDto));
    }
}
