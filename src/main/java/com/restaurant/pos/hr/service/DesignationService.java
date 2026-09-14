package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.dto.DesignationDto;
import com.restaurant.pos.hr.entity.Designation;
import com.restaurant.pos.hr.repository.DesignationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DesignationService {

    private final DesignationRepository designationRepository;

    @Transactional(readOnly = true)
    public List<DesignationDto> getAllDesignations() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        return designationRepository.findByClientIdAndOrgId(clientId, orgId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DesignationDto getDesignationById(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        return designationRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .map(this::mapToDto)
                .orElseThrow(() -> new RuntimeException("Designation not found"));
    }

    @Transactional
    public DesignationDto createDesignation(DesignationDto dto) {
        Designation designation = new Designation();
        designation.setName(dto.getName());
        designation.setDescription(dto.getDescription());
        designation.setActive(dto.isActive());
        
        Designation saved = designationRepository.save(designation);
        return mapToDto(saved);
    }

    @Transactional
    public DesignationDto updateDesignation(UUID id, DesignationDto dto) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        Designation designation = designationRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("Designation not found"));
                
        designation.setName(dto.getName());
        designation.setDescription(dto.getDescription());
        designation.setActive(dto.isActive());
        
        Designation saved = designationRepository.save(designation);
        return mapToDto(saved);
    }

    @Transactional
    public void deleteDesignation(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        Designation designation = designationRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("Designation not found"));
                designation.setActive(false);
        designationRepository.save(designation);
    }

    private DesignationDto mapToDto(Designation entity) {
        return DesignationDto.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .isActive(entity.isActive())
                .build();
    }
}
