package com.restaurant.pos.credit.api;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.credit.dto.CreditBPartnerDto;
import com.restaurant.pos.credit.dto.CreditOrderDto;
import com.restaurant.pos.credit.dto.CreditReportDto;
import com.restaurant.pos.credit.query.CreditQueryService;
import com.restaurant.pos.subscription.annotation.RequireModule;
import com.restaurant.pos.subscription.domain.ModuleName;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CQRS Query Controller for the Credit module.
 * Handles read-only operations: list customers/partners, orders, payments, report.
 */
@Validated
@RestController
@RequestMapping("/api/v1/credit")
@RequiredArgsConstructor
@RequireModule(ModuleName.CREDIT_LEDGER)
@Tag(name = "Credit Management", description = "Read-only operations for customer and partner credit ledgers.")
public class CreditQueryController {

    private final CreditQueryService queryService;

    @GetMapping({"/customers", "/partners"})
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "List credit customers and partners", description = "Retrieves credit status and ledgers for customers or vendors.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved partner list"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<List<CreditBPartnerDto>>> listCustomers(
            @Parameter(description = "Optional filter by status (e.g. ACTIVE, SUSPENDED)") @RequestParam(required = false) String status,
            @Parameter(description = "Optional filter by partner type (CUSTOMER or VENDOR)") @RequestParam(required = false) String partnerType) {
        return ResponseEntity.ok(ApiResponse.success(queryService.listPartners(status, partnerType)));
    }

    @GetMapping({"/customers/{id}/orders", "/partners/{id}/orders"})
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Get credit orders", description = "Retrieves paginated credit orders/invoices for a given customer or partner.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved credit orders"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid pagination parameter"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer or partner not found")
    })
    public ResponseEntity<ApiResponse<Page<CreditOrderDto>>> getCustomerOrders(
            @Parameter(description = "UUID of the customer or partner", required = true) @PathVariable UUID id,
            @Parameter(description = "Optional partner type filter") @RequestParam(required = false) String partnerType,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "Page index must be 0 or greater") int page,
            @RequestParam(defaultValue = "50") @Min(value = 1, message = "Page size must be at least 1") @Max(value = 500, message = "Page size cannot exceed 500") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                queryService.getPartnerOrders(id, PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))), partnerType)));
    }

    @GetMapping({"/customers/{id}/payments", "/partners/{id}/payments"})
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Get credit payment history", description = "Retrieves paginated payment history for a given customer or partner.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully retrieved payment history"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid pagination parameter"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer or partner not found")
    })
    public ResponseEntity<ApiResponse<Page<CreditReportDto.PaymentTransactionDto>>> getCustomerPayments(
            @Parameter(description = "UUID of the customer or partner", required = true) @PathVariable UUID id,
            @Parameter(description = "Optional partner type filter") @RequestParam(required = false) String partnerType,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "Page index must be 0 or greater") int page,
            @RequestParam(defaultValue = "50") @Min(value = 1, message = "Page size must be at least 1") @Max(value = 500, message = "Page size cannot exceed 500") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                queryService.getPartnerPayments(id, PageRequest.of(page, size, Sort.by(Sort.Order.desc("paymentDate"), Sort.Order.desc("id"))), partnerType)));
    }

    @GetMapping("/report")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Get credit ledger report", description = "Retrieves summary credit report and payment transactions within an optional date range.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Successfully generated credit report"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid date range parameters"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Unauthorized")
    })
    public ResponseEntity<ApiResponse<CreditReportDto>> report(
            @Parameter(description = "Start instant filter (ISO-8601)") @RequestParam(required = false) Instant from,
            @Parameter(description = "End instant filter (ISO-8601)") @RequestParam(required = false) Instant to) {
        return ResponseEntity.ok(ApiResponse.success(queryService.report(from, to)));
    }
}
