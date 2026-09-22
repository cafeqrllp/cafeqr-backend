package com.restaurant.pos.order.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.order.dto.report.PaymentBalanceReportDto;
import com.restaurant.pos.order.service.PaymentBalanceReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Dedicated Controller solely serving the Payment Type Balances report.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class PaymentBalanceReportController {

    private final PaymentBalanceReportService paymentBalanceReportService;

    @GetMapping("/payment-balances")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<PaymentBalanceReportDto>> getPaymentBalances(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID terminalId) {
        validateReportRange(from, to);
        return ResponseEntity.ok(ApiResponse.success(
                paymentBalanceReportService.getPaymentTypeBalances(from, to, orgId, terminalId)
        ));
    }

    private void validateReportRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Report from date must be before to date");
        }
    }
}
