package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.dto.DepartmentDto;
import com.restaurant.pos.hr.entity.Department;
import com.restaurant.pos.hr.repository.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;

    @Transactional(readOnly = true)
    public List<DepartmentDto> getAllDepartments() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        return departmentRepository.findByClientIdAndOrgId(clientId, orgId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DepartmentDto getDepartmentById(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        return departmentRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .map(this::mapToDto)
                .orElseThrow(() -> new RuntimeException("Department not found"));
    }

    @Transactional
    public DepartmentDto createDepartment(DepartmentDto dto) {
        Department department = new Department();
        department.setName(dto.getName());
        department.setDescription(dto.getDescription());
        department.setActive(dto.isActive());
        
        Department saved = departmentRepository.save(department);
        return mapToDto(saved);
    }

    @Transactional
    public DepartmentDto updateDepartment(UUID id, DepartmentDto dto) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        Department department = departmentRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("Department not found"));
                
        department.setName(dto.getName());
        department.setDescription(dto.getDescription());
        department.setActive(dto.isActive());
        
        Department saved = departmentRepository.save(department);
        return mapToDto(saved);
    }

    @Transactional
    public void deleteDepartment(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        Department department = departmentRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("Department not found"));
                department.setActive(false);
        departmentRepository.save(department);
    }

    private DepartmentDto mapToDto(Department entity) {
        return DepartmentDto.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .isActive(entity.isActive())
                .build();
    }
}
