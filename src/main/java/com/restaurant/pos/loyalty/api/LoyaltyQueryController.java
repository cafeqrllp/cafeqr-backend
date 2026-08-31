package com.restaurant.pos.loyalty.api;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.loyalty.dto.CustomerLoyaltyDto;
import com.restaurant.pos.loyalty.dto.LoyaltyProgramDto;
import com.restaurant.pos.loyalty.dto.LoyaltyTransactionDto;
import com.restaurant.pos.loyalty.query.LoyaltyQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * CQRS Query Controller for Loyalty Module.
 * Handles read operations (GET PROGRAMS, GET PROGRAM BY ID, GET CUSTOMER LOYALTY, GET TRANSACTIONS).
 * Lives in feature-based package 'loyalty.api'.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/loyalty")
@RequiredArgsConstructor
@Validated
public class LoyaltyQueryController {

    private final LoyaltyQueryService queryService;

    @GetMapping("/programs")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<LoyaltyProgramDto>>> getPrograms() {
        return ResponseEntity.ok(ApiResponse.success(queryService.getPrograms()));
    }

    @GetMapping("/programs/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<LoyaltyProgramDto>> getProgram(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(queryService.getProgram(id)));
    }

    @GetMapping("/customers/{customerId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<CustomerLoyaltyDto>> getCustomerLoyalty(@PathVariable UUID customerId) {
        return ResponseEntity.ok(ApiResponse.success(queryService.getCustomerLoyalty(customerId)));
    }

    @GetMapping("/customers/{customerId}/transactions")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<Page<LoyaltyTransactionDto>>> getTransactions(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(queryService.getTransactions(customerId, page, size)));
    }
}
