package com.restaurant.pos.hr.controller;

import com.restaurant.pos.hr.dto.DesignationDto;
import com.restaurant.pos.hr.service.DesignationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hr/designations")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
public class DesignationController {

    private final DesignationService designationService;

    @GetMapping
    public ResponseEntity<List<DesignationDto>> getAllDesignations() {
        return ResponseEntity.ok(designationService.getAllDesignations());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DesignationDto> getDesignationById(@PathVariable UUID id) {
        return ResponseEntity.ok(designationService.getDesignationById(id));
    }

    @PostMapping
    public ResponseEntity<DesignationDto> createDesignation(@RequestBody DesignationDto dto) {
        return ResponseEntity.ok(designationService.createDesignation(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DesignationDto> updateDesignation(@PathVariable UUID id, @RequestBody DesignationDto dto) {
        return ResponseEntity.ok(designationService.updateDesignation(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDesignation(@PathVariable UUID id) {
        designationService.deleteDesignation(id);
        return ResponseEntity.noContent().build();
    }
}
