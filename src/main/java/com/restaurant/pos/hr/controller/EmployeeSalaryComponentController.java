package com.restaurant.pos.hr.controller;

import com.restaurant.pos.hr.dto.EmployeeSalaryComponentDto;
import com.restaurant.pos.hr.service.EmployeeSalaryComponentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hr/employees/{employeeId}/components")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
public class EmployeeSalaryComponentController {

    private final EmployeeSalaryComponentService employeeSalaryComponentService;

    @GetMapping
    public ResponseEntity<List<EmployeeSalaryComponentDto>> getEmployeeSalaryComponents(@PathVariable UUID employeeId) {
        return ResponseEntity.ok(employeeSalaryComponentService.getComponentsForEmployee(employeeId));
    }

    @PostMapping
    public ResponseEntity<EmployeeSalaryComponentDto> assignSalaryComponent(
            @PathVariable UUID employeeId,
            @RequestBody EmployeeSalaryComponentDto dto) {
        return ResponseEntity.ok(employeeSalaryComponentService.assignComponentToEmployee(employeeId, dto));
    }

    @DeleteMapping("/{salaryComponentId}")
    public ResponseEntity<Void> removeSalaryComponent(
            @PathVariable UUID employeeId,
            @PathVariable UUID salaryComponentId) {
        employeeSalaryComponentService.removeComponentFromEmployee(employeeId, salaryComponentId);
        return ResponseEntity.noContent().build();
    }
}
