package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.dto.SalaryComponentDto;
import com.restaurant.pos.hr.entity.SalaryComponent;
import com.restaurant.pos.hr.repository.SalaryComponentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SalaryComponentService {

    private final SalaryComponentRepository salaryComponentRepository;

    @Transactional(readOnly = true)
    public List<SalaryComponentDto> getAllComponents() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        return salaryComponentRepository.findByClientIdAndOrgIdOrGlobal(clientId, orgId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public SalaryComponentDto createComponent(SalaryComponentDto dto) {
        SalaryComponent component = new SalaryComponent();
        component.setName(dto.getName());
        component.setType(dto.getType());
        component.setTaxApplicable(dto.isTaxApplicable());
        component.setDependsOnAttendance(dto.isDependsOnAttendance());
        component.setAmountType(dto.getAmountType());
        component.setDefaultAmount(dto.getDefaultAmount());
        component.setPercentage(dto.getPercentage());
        component.setPercentageOfComponent(dto.getPercentageOfComponent());
        component.setActive(dto.isActive());
        
        SalaryComponent saved = salaryComponentRepository.save(component);
        return mapToDto(saved);
    }

    @Transactional
    public SalaryComponentDto updateComponent(UUID id, SalaryComponentDto dto) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        SalaryComponent component = salaryComponentRepository.findByIdAndClientIdAndOrgIdOrGlobal(id, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("SalaryComponent not found"));
                
        component.setName(dto.getName());
        component.setType(dto.getType());
        component.setTaxApplicable(dto.isTaxApplicable());
        component.setDependsOnAttendance(dto.isDependsOnAttendance());
        component.setAmountType(dto.getAmountType());
        component.setDefaultAmount(dto.getDefaultAmount());
        component.setPercentage(dto.getPercentage());
        component.setPercentageOfComponent(dto.getPercentageOfComponent());
        component.setActive(dto.isActive());
        
        SalaryComponent saved = salaryComponentRepository.save(component);
        return mapToDto(saved);
    }

    @Transactional
    public void deleteComponent(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        SalaryComponent component = salaryComponentRepository.findByIdAndClientIdAndOrgIdOrGlobal(id, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("SalaryComponent not found"));
                
        salaryComponentRepository.delete(component);
    }

    private SalaryComponentDto mapToDto(SalaryComponent entity) {
        return SalaryComponentDto.builder()
                .id(entity.getId())
                .name(entity.getName())
                .type(entity.getType())
                .isTaxApplicable(entity.isTaxApplicable())
                .dependsOnAttendance(entity.isDependsOnAttendance())
                .amountType(entity.getAmountType())
                .defaultAmount(entity.getDefaultAmount())
                .percentage(entity.getPercentage())
                .percentageOfComponent(entity.getPercentageOfComponent())
                .isActive(entity.isActive())
                .build();
    }
}
