package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.dto.EmployeeSalaryComponentDto;
import com.restaurant.pos.hr.entity.Employee;
import com.restaurant.pos.hr.entity.EmployeeSalaryComponent;
import com.restaurant.pos.hr.entity.SalaryComponent;
import com.restaurant.pos.hr.repository.EmployeeRepository;
import com.restaurant.pos.hr.repository.EmployeeSalaryComponentRepository;
import com.restaurant.pos.hr.repository.SalaryComponentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeSalaryComponentService {

    private final EmployeeSalaryComponentRepository employeeSalaryComponentRepository;
    private final EmployeeRepository employeeRepository;
    private final SalaryComponentRepository salaryComponentRepository;

    @Transactional(readOnly = true)
    public List<EmployeeSalaryComponentDto> getComponentsForEmployee(UUID employeeId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        employeeRepository.findByIdAndClientIdAndOrgId(employeeId, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        return employeeSalaryComponentRepository.findByEmployeeId(employeeId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public EmployeeSalaryComponentDto assignComponentToEmployee(UUID employeeId, EmployeeSalaryComponentDto dto) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        Employee employee = employeeRepository.findByIdAndClientIdAndOrgId(employeeId, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        SalaryComponent component = salaryComponentRepository.findByIdAndClientIdAndOrgIdOrGlobal(dto.getSalaryComponentId(), clientId, orgId)
                .orElseThrow(() -> new RuntimeException("SalaryComponent not found"));

        EmployeeSalaryComponent empComp = employeeSalaryComponentRepository
                .findByEmployeeIdAndSalaryComponentId(employeeId, dto.getSalaryComponentId())
                .orElseGet(() -> {
                    EmployeeSalaryComponent newComp = new EmployeeSalaryComponent();
                    newComp.setEmployee(employee);
                    newComp.setSalaryComponent(component);
                    return newComp;
                });

        empComp.setOverrideAmount(dto.getOverrideAmount());
        empComp.setOverridePercentage(dto.getOverridePercentage());
        empComp.setActive(dto.isActive());

        EmployeeSalaryComponent saved = employeeSalaryComponentRepository.save(empComp);
        return mapToDto(saved);
    }

    @Transactional
    public void removeComponentFromEmployee(UUID employeeId, UUID salaryComponentId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        employeeRepository.findByIdAndClientIdAndOrgId(employeeId, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        EmployeeSalaryComponent empComp = employeeSalaryComponentRepository
                .findByEmployeeIdAndSalaryComponentId(employeeId, salaryComponentId)
                .orElseThrow(() -> new RuntimeException("Salary component assignment not found for employee"));

        employeeSalaryComponentRepository.delete(empComp);
    }

    private EmployeeSalaryComponentDto mapToDto(EmployeeSalaryComponent entity) {
        SalaryComponent sc = entity.getSalaryComponent();
        return EmployeeSalaryComponentDto.builder()
                .id(entity.getId())
                .employeeId(entity.getEmployee().getId())
                .salaryComponentId(sc != null ? sc.getId() : null)
                .componentName(sc != null ? sc.getName() : null)
                .componentType(sc != null ? sc.getType() : null)
                .amountType(sc != null ? sc.getAmountType() : null)
                .defaultAmount(sc != null ? sc.getDefaultAmount() : null)
                .percentage(sc != null ? sc.getPercentage() : null)
                .overrideAmount(entity.getOverrideAmount())
                .overridePercentage(entity.getOverridePercentage())
                .isActive(entity.isActive())
                .build();
    }
}
