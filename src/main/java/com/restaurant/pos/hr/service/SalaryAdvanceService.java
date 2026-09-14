package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.dto.SalaryAdvanceDto;
import com.restaurant.pos.hr.entity.Employee;
import com.restaurant.pos.hr.entity.SalaryAdvance;
import com.restaurant.pos.hr.repository.EmployeeRepository;
import com.restaurant.pos.hr.repository.SalaryAdvanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SalaryAdvanceService {

    private final SalaryAdvanceRepository salaryAdvanceRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public List<SalaryAdvanceDto> getAllAdvances() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        return salaryAdvanceRepository.findByClientIdAndOrgId(clientId, orgId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }
    
    @Transactional(readOnly = true)
    public List<SalaryAdvanceDto> getAdvancesByEmployee(UUID employeeId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        return salaryAdvanceRepository.findByEmployeeIdAndClientIdAndOrgId(employeeId, clientId, orgId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public SalaryAdvanceDto createAdvance(SalaryAdvanceDto dto) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        Employee employee = employeeRepository.findByIdAndClientIdAndOrgId(dto.getEmployeeId(), clientId, orgId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        SalaryAdvance advance = new SalaryAdvance();
        advance.setEmployee(employee);
        advance.setAdvanceDate(dto.getAdvanceDate());
        advance.setTotalAmount(dto.getTotalAmount());
        advance.setMonthlyInstallmentAmount(dto.getMonthlyInstallmentAmount());
        advance.setRemainingBalance(dto.getTotalAmount()); // Initially, remaining balance is total amount
        advance.setReason(dto.getReason());
        advance.setStatus("PENDING");
        
        SalaryAdvance saved = salaryAdvanceRepository.save(advance);
        return mapToDto(saved);
    }

    @Transactional
    public SalaryAdvanceDto updateAdvanceStatus(UUID id, String status) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        SalaryAdvance advance = salaryAdvanceRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("SalaryAdvance not found"));
                
        advance.setStatus(status);
        SalaryAdvance saved = salaryAdvanceRepository.save(advance);
        return mapToDto(saved);
    }

    @Transactional
    public SalaryAdvanceDto updateAdvance(UUID id, SalaryAdvanceDto dto) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        SalaryAdvance advance = salaryAdvanceRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("SalaryAdvance not found"));

        if (dto.getEmployeeId() != null) {
            Employee employee = employeeRepository.findByIdAndClientIdAndOrgId(dto.getEmployeeId(), clientId, orgId)
                    .orElseThrow(() -> new RuntimeException("Employee not found"));
            advance.setEmployee(employee);
        }

        if (dto.getAdvanceDate() != null) advance.setAdvanceDate(dto.getAdvanceDate());
        if (dto.getTotalAmount() != null) advance.setTotalAmount(dto.getTotalAmount());
        if (dto.getMonthlyInstallmentAmount() != null) advance.setMonthlyInstallmentAmount(dto.getMonthlyInstallmentAmount());
        if (dto.getRemainingBalance() != null) advance.setRemainingBalance(dto.getRemainingBalance());
        if (dto.getReason() != null) advance.setReason(dto.getReason());
        if (dto.getStatus() != null) advance.setStatus(dto.getStatus());

        SalaryAdvance saved = salaryAdvanceRepository.save(advance);
        return mapToDto(saved);
    }

    @Transactional
    public void deleteAdvance(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        SalaryAdvance advance = salaryAdvanceRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("SalaryAdvance not found"));
                
        salaryAdvanceRepository.delete(advance);
    }

    private SalaryAdvanceDto mapToDto(SalaryAdvance entity) {
        return SalaryAdvanceDto.builder()
                .id(entity.getId())
                .employeeId(entity.getEmployee().getId())
                .employeeName(entity.getEmployee().getFirstName() + " " + entity.getEmployee().getLastName())
                .advanceDate(entity.getAdvanceDate())
                .totalAmount(entity.getTotalAmount())
                .monthlyInstallmentAmount(entity.getMonthlyInstallmentAmount())
                .remainingBalance(entity.getRemainingBalance())
                .reason(entity.getReason())
                .status(entity.getStatus())
                .build();
    }
}
