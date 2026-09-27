package com.restaurant.pos.paymenttype.api;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.security.StaffAccess;
import com.restaurant.pos.paymenttype.command.PaymentTypeCommand;
import com.restaurant.pos.paymenttype.command.PaymentTypeCommandService;
import com.restaurant.pos.paymenttype.domain.PaymentType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * CQRS Command Controller for PaymentType master data.
 * Handles state mutations: CREATE, UPDATE, DELETE.
 */
@Slf4j
@StaffAccess
@RestController
@RequestMapping("/api/v1/payment-types")
@RequiredArgsConstructor
@Validated
@Tag(name = "Payment Type Commands", description = "Endpoints for creating, updating, and deleting Payment Types.")
public class PaymentTypeCommandController {

    private final PaymentTypeCommandService commandService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    @Operation(
        summary = "Create Payment Type",
        description = "Creates a new Payment Type for the current branch."
    )
    public ResponseEntity<ApiResponse<PaymentType>> createPaymentType(
            @Parameter(description = "Payment type details", required = true)
            @RequestBody PaymentTypeCommand command) {

        log.info("Creating payment type | name={}", command.getDisplayName());
        PaymentType created = commandService.createPaymentType(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    @Operation(
        summary = "Update Payment Type",
        description = "Updates an existing Payment Type by ID."
    )
    public ResponseEntity<ApiResponse<PaymentType>> updatePaymentType(
            @Parameter(description = "UUID of the Payment Type to update", required = true) @PathVariable UUID id,
            @Parameter(description = "Updated payment type details", required = true) @RequestBody PaymentTypeCommand command) {

        log.info("Updating payment type | id={}", id);
        PaymentType updated = commandService.updatePaymentType(id, command);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    @Operation(
        summary = "Delete Payment Type",
        description = "Deletes a Payment Type by ID."
    )
    public ResponseEntity<ApiResponse<Void>> deletePaymentType(
            @Parameter(description = "UUID of the Payment Type to delete", required = true) @PathVariable UUID id) {

        log.info("Deleting payment type | id={}", id);
        commandService.deletePaymentType(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}