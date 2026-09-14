package com.restaurant.pos.hr.controller;

import com.restaurant.pos.hr.dto.PayrollRunDto;
import com.restaurant.pos.hr.dto.SalarySlipDto;
import com.restaurant.pos.hr.service.PayrollEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hr/payroll")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
public class PayrollEngineController {

    private final PayrollEngineService payrollEngineService;

    @PostMapping("/run")
    public ResponseEntity<PayrollRunDto> initiatePayrollRun(@RequestBody PayrollRunDto dto) {
        return ResponseEntity.ok(payrollEngineService.initiatePayrollRun(dto));
    }

    @GetMapping("/runs")
    public ResponseEntity<List<PayrollRunDto>> getAllPayrollRuns() {
        return ResponseEntity.ok(payrollEngineService.getAllPayrollRuns());
    }

    @GetMapping("/runs/{runId}/slips")
    public ResponseEntity<List<SalarySlipDto>> getSlipsForRun(@PathVariable UUID runId) {
        return ResponseEntity.ok(payrollEngineService.getSlipsForRun(runId));
    }

    @DeleteMapping("/runs/{runId}")
    public ResponseEntity<Void> deletePayrollRun(@PathVariable UUID runId) {
        payrollEngineService.deletePayrollRun(runId);
        return ResponseEntity.noContent().build();
    }
}
