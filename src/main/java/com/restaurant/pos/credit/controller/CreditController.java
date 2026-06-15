package com.restaurant.pos.credit.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.credit.dto.CreditCustomerDto;
import com.restaurant.pos.credit.dto.CreditCustomerRequest;
import com.restaurant.pos.credit.dto.CreditOrderDto;
import com.restaurant.pos.credit.dto.CreditPaymentRequest;
import com.restaurant.pos.credit.dto.CreditReportDto;
import com.restaurant.pos.credit.service.CreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/credit")
@RequiredArgsConstructor
public class CreditController {

    private final CreditService creditService;

    @GetMapping("/customers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<CreditCustomerDto>>> listCustomers(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.success(creditService.listCustomers(status)));
    }

    @PostMapping("/customers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<CreditCustomerDto>> createCustomer(@RequestBody CreditCustomerRequest request) {
        return ResponseEntity.ok(ApiResponse.success(creditService.createCustomer(request)));
    }

    @PutMapping("/customers/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<CreditCustomerDto>> updateCustomer(@PathVariable UUID id, @RequestBody CreditCustomerRequest request) {
        return ResponseEntity.ok(ApiResponse.success(creditService.updateCustomer(id, request)));
    }

    @PostMapping("/customers/{id}/suspend")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<CreditCustomerDto>> suspendCustomer(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(creditService.suspendCustomer(id)));
    }

    @PostMapping("/customers/{id}/reactivate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<CreditCustomerDto>> reactivateCustomer(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(creditService.reactivateCustomer(id)));
    }

    @GetMapping("/customers/{id}/orders")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<CreditOrderDto>>> getCustomerOrders(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(creditService.getCustomerOrders(id)));
    }

    @GetMapping("/customers/{id}/payments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<CreditReportDto.PaymentTransactionDto>>> getCustomerPayments(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(creditService.getCustomerPayments(id)));
    }

    @PostMapping("/customers/{id}/payments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<CreditCustomerDto>> recordPayment(@PathVariable UUID id, @RequestBody CreditPaymentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(creditService.recordPayment(id, request)));
    }

    @GetMapping("/report")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<CreditReportDto>> report(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return ResponseEntity.ok(ApiResponse.success(creditService.report(from, to)));
    }
}
