package com.restaurant.pos.credit.api;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.idempotency.IdempotencyGuard;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.credit.command.CreditCommandService;
import com.restaurant.pos.credit.command.RecordPaymentCommand;
import com.restaurant.pos.credit.dto.CreateCreditCustomerRequest;
import com.restaurant.pos.credit.dto.CreditBPartnerDto;
import com.restaurant.pos.credit.dto.CreditPaymentRequest;
import com.restaurant.pos.subscription.annotation.RequireModule;
import com.restaurant.pos.subscription.domain.ModuleName;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CQRS Command Controller for the Credit module.
 * Handles state-mutating operations: suspend, reactivate, record payment.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/credit")
@RequiredArgsConstructor
@RequireModule(ModuleName.CREDIT_LEDGER)
@Tag(name = "Credit Management", description = "Endpoints for managing customer and vendor credit ledgers, status, and payments.")
public class CreditCommandController {

    private final CreditCommandService commandService;
    private final IdempotencyGuard idempotencyGuard;
    private final BranchContextService branchContext;

    @PostMapping({"/customers", "/partners"})
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Create credit customer", description = "Establishes a new credit customer account.")
    public ResponseEntity<ApiResponse<CreditBPartnerDto>> createCreditCustomer(
            @Valid @RequestBody CreateCreditCustomerRequest request) {
        log.info("Creating new credit customer | name={} | phone={}", request.getName(), request.getPhone());
        return ResponseEntity.ok(ApiResponse.success(commandService.createCreditCustomer(request)));
    }

    @PutMapping({"/customers/{id}", "/partners/{id}"})
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Update credit customer", description = "Updates details of a credit customer account.")
    public ResponseEntity<ApiResponse<CreditBPartnerDto>> updateCreditCustomer(
            @PathVariable UUID id,
            @Valid @RequestBody CreateCreditCustomerRequest request) {
        log.info("Updating credit customer | id={} | name={}", id, request.getName());
        return ResponseEntity.ok(ApiResponse.success(commandService.updateCreditCustomer(id, request)));
    }

    @DeleteMapping({"/customers/{id}", "/partners/{id}"})
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    @Operation(summary = "Delete credit customer", description = "Deactivates/deletes a credit customer account.")
    public ResponseEntity<ApiResponse<Void>> deleteCreditCustomer(@PathVariable UUID id) {
        log.info("Deleting credit customer | id={}", id);
        commandService.deleteCreditCustomer(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping({"/customers/{id}/suspend", "/partners/{id}/suspend"})
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    @Operation(summary = "Suspend customer credit", description = "Suspends credit privileges for a customer or partner.")
    public ResponseEntity<ApiResponse<CreditBPartnerDto>> suspendCustomer(
            @Parameter(description = "UUID of the customer or partner to suspend", required = true) @PathVariable UUID id) {
        log.info("Suspending credit customer/partner | id={}", id);
        return ResponseEntity.ok(ApiResponse.success(commandService.suspendCustomer(id)));
    }

    @PostMapping({"/customers/{id}/reactivate", "/partners/{id}/reactivate"})
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    @Operation(summary = "Reactivate customer credit", description = "Reactivates credit status for a customer or partner.")
    public ResponseEntity<ApiResponse<CreditBPartnerDto>> reactivateCustomer(
            @Parameter(description = "UUID of the customer or partner to reactivate", required = true) @PathVariable UUID id) {
        log.info("Reactivating credit customer/partner | id={}", id);
        return ResponseEntity.ok(ApiResponse.success(commandService.reactivateCustomer(id)));
    }

    @PostMapping({"/customers/{id}/payments", "/partners/{id}/payments"})
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @Operation(summary = "Record credit payment", description = "Records a payment against a customer or vendor credit ledger with optional invoice allocation.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Payment recorded successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation or business rule failure"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Customer, partner, or invoice not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Idempotency key lock or concurrent duplicate request conflict"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Unprocessable entity or semantic validation error"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Unexpected internal server error")
    })
    public ResponseEntity<ApiResponse<CreditBPartnerDto>> recordPayment(
            @Parameter(description = "UUID of the customer or partner", required = true) @PathVariable UUID id,
            @Parameter(description = "Idempotency key to prevent duplicate payments", required = false) @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreditPaymentRequest request) {

        final String effectiveIdempotencyKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey
                : "auto_credit_pay_" + id + "_" + System.currentTimeMillis() + "_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        UUID writeOrgId = branchContext.requireWriteOrgId(request.getOrgId());
        log.info("Recording credit payment | partnerId={} | orgId={} | method={}", id, writeOrgId, request.getPaymentMethod());

        CreditBPartnerDto result = idempotencyGuard.execute(
                "credit-payment",
                id,
                effectiveIdempotencyKey,
                CreditBPartnerDto.class,
                () -> {
                    RecordPaymentCommand command = RecordPaymentCommand.builder()
                            .creditCustomerId(id)
                            .orgId(writeOrgId)
                            .amount(request.getAmount())
                            .paymentMethod(request.getPaymentMethod())
                            .description(request.getDescription())
                            .allocationMode(request.getAllocationMode())
                            .invoiceId(request.getInvoiceId())
                            .partnerType(request.getPartnerType())
                            .idempotencyKey(effectiveIdempotencyKey)
                            .allocations(request.getAllocations() != null
                                    ? request.getAllocations().stream()
                                            .map(a -> RecordPaymentCommand.AllocationEntry.builder()
                                                    .invoiceId(a.getInvoiceId())
                                                    .amount(a.getAmount())
                                                    .build())
                                            .collect(Collectors.toList())
                                    : null)
                            .build();
                    return commandService.recordPayment(command);
                }
        );

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}

