package com.restaurant.pos.hr.controller;

import com.restaurant.pos.hr.dto.LeaveRequestDto;
import com.restaurant.pos.hr.service.LeaveRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hr/leaves")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
public class LeaveRequestController {

    private final LeaveRequestService leaveRequestService;

    @GetMapping
    public ResponseEntity<List<LeaveRequestDto>> getAllLeaveRequests() {
        return ResponseEntity.ok(leaveRequestService.getAllLeaveRequests());
    }
    
    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<List<LeaveRequestDto>> getLeaveRequestsByEmployee(@PathVariable UUID employeeId) {
        return ResponseEntity.ok(leaveRequestService.getLeaveRequestsByEmployee(employeeId));
    }

    @PostMapping
    public ResponseEntity<LeaveRequestDto> createLeaveRequest(@RequestBody LeaveRequestDto dto) {
        return ResponseEntity.ok(leaveRequestService.createLeaveRequest(dto));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<LeaveRequestDto> updateLeaveStatus(
            @PathVariable UUID id,
            @RequestParam String status) {
        return ResponseEntity.ok(leaveRequestService.updateLeaveStatus(id, status));
    }

    @PutMapping("/{id}")
    public ResponseEntity<LeaveRequestDto> updateLeaveRequest(
            @PathVariable UUID id,
            @RequestBody LeaveRequestDto dto) {
        return ResponseEntity.ok(leaveRequestService.updateLeaveRequest(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLeaveRequest(@PathVariable UUID id) {
        leaveRequestService.deleteLeaveRequest(id);
        return ResponseEntity.noContent().build();
    }
}
