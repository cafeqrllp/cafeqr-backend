package com.restaurant.pos.hr.controller;

import com.restaurant.pos.hr.dto.SalaryAdvanceDto;
import com.restaurant.pos.hr.service.SalaryAdvanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hr/advances")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
public class SalaryAdvanceController {

    private final SalaryAdvanceService salaryAdvanceService;

    @GetMapping
    public ResponseEntity<List<SalaryAdvanceDto>> getAllAdvances() {
        return ResponseEntity.ok(salaryAdvanceService.getAllAdvances());
    }
    
    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<SalaryAdvanceDto>> getAdvancesByEmployee(@PathVariable UUID employeeId) {
        return ResponseEntity.ok(salaryAdvanceService.getAdvancesByEmployee(employeeId));
    }

    @PostMapping
    public ResponseEntity<SalaryAdvanceDto> createAdvance(@RequestBody SalaryAdvanceDto dto) {
        return ResponseEntity.ok(salaryAdvanceService.createAdvance(dto));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<SalaryAdvanceDto> updateAdvanceStatus(
            @PathVariable UUID id,
            @RequestParam String status) {
        return ResponseEntity.ok(salaryAdvanceService.updateAdvanceStatus(id, status));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SalaryAdvanceDto> updateAdvance(
            @PathVariable UUID id,
            @RequestBody SalaryAdvanceDto dto) {
        return ResponseEntity.ok(salaryAdvanceService.updateAdvance(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAdvance(@PathVariable UUID id) {
        salaryAdvanceService.deleteAdvance(id);
        return ResponseEntity.noContent().build();
    }
}
