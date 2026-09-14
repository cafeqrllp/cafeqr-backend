package com.restaurant.pos.hr.controller;

import com.restaurant.pos.hr.service.PayrollAccountingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hr/payroll-accounting")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
public class PayrollAccountingController {

    private final PayrollAccountingService payrollAccountingService;

    @PostMapping("/sync/{payrollRunId}")
    public ResponseEntity<Void> syncPayrollToAccounting(
            @PathVariable UUID payrollRunId,
            @RequestParam(required = false) String paymentMethod) {
        payrollAccountingService.syncPayrollToAccounting(payrollRunId, paymentMethod);
        return ResponseEntity.ok().build();
    }
}
