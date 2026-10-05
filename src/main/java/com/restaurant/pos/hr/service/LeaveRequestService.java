package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.dto.LeaveRequestDto;
import com.restaurant.pos.hr.entity.Attendance;
import com.restaurant.pos.hr.entity.Employee;
import com.restaurant.pos.hr.entity.LeaveRequest;
import com.restaurant.pos.hr.repository.AttendanceRepository;
import com.restaurant.pos.hr.repository.EmployeeRepository;
import com.restaurant.pos.hr.repository.LeaveRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeaveRequestService {

    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceRepository attendanceRepository;

    @Transactional(readOnly = true)
    public List<LeaveRequestDto> getAllLeaveRequests() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        return leaveRequestRepository.findByClientIdAndOrgId(clientId, orgId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }
    
    @Transactional(readOnly = true)
    public List<LeaveRequestDto> getLeaveRequestsByEmployee(UUID employeeId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        return leaveRequestRepository.findByEmployeeIdAndClientIdAndOrgId(employeeId, clientId, orgId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public LeaveRequestDto createLeaveRequest(LeaveRequestDto dto) {
        if (dto.getStartDate() == null || dto.getEndDate() == null) {
            throw new IllegalArgumentException("Start date and end date are required.");
        }
        if (dto.getStartDate().isAfter(dto.getEndDate())) {
            throw new IllegalArgumentException("End date cannot be earlier than start date.");
        }
        int startYear = dto.getStartDate().getYear();
        int endYear = dto.getEndDate().getYear();
        if (startYear < 2000 || startYear > 2100 || endYear < 2000 || endYear > 2100) {
            throw new IllegalArgumentException("Invalid date range: Year must be between 2000 and 2100.");
        }

        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        Employee employee = employeeRepository.findByIdAndClientIdAndOrgId(dto.getEmployeeId(), clientId, orgId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        LeaveRequest leave = new LeaveRequest();
        leave.setEmployee(employee);
        leave.setLeaveType(dto.getLeaveType());
        leave.setStartDate(dto.getStartDate());
        leave.setEndDate(dto.getEndDate());
        
        long calculatedDays = java.time.temporal.ChronoUnit.DAYS.between(dto.getStartDate(), dto.getEndDate()) + 1;
        leave.setTotalDays((int) calculatedDays);
        leave.setReason(dto.getReason());
        leave.setStatus(dto.getStatus() != null ? dto.getStatus() : "PENDING");
        
        LeaveRequest saved = leaveRequestRepository.save(leave);
        return mapToDto(saved);
    }

    @Transactional
    public LeaveRequestDto updateLeaveStatus(UUID id, String status) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        LeaveRequest leave = leaveRequestRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("LeaveRequest not found"));

        // Block approval if attendance records exist on the leave dates
        if ("APPROVED".equalsIgnoreCase(status)) {
            List<Attendance> conflicting = attendanceRepository.findPresentByEmployeeIdAndDateRange(
                    leave.getEmployee().getId(), leave.getStartDate(), leave.getEndDate(), clientId, orgId);
            if (!conflicting.isEmpty()) {
                String dates = conflicting.stream()
                        .map(a -> a.getAttendanceDate().toString())
                        .distinct()
                        .collect(java.util.stream.Collectors.joining(", "));
                throw new RuntimeException("Cannot approve leave: Employee has attendance records on: "
                        + dates + ". Remove those attendance entries first.");
            }
        }

        leave.setStatus(status);
        LeaveRequest saved = leaveRequestRepository.save(leave);
        return mapToDto(saved);
    }

    @Transactional
    public LeaveRequestDto updateLeaveRequest(UUID id, LeaveRequestDto dto) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        LeaveRequest leave = leaveRequestRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("LeaveRequest not found"));

        if (dto.getEmployeeId() != null) {
            Employee employee = employeeRepository.findByIdAndClientIdAndOrgId(dto.getEmployeeId(), clientId, orgId)
                    .orElseThrow(() -> new RuntimeException("Employee not found"));
            leave.setEmployee(employee);
        }

        if (dto.getLeaveType() != null) leave.setLeaveType(dto.getLeaveType());
        if (dto.getStartDate() != null) leave.setStartDate(dto.getStartDate());
        if (dto.getEndDate() != null) leave.setEndDate(dto.getEndDate());

        if (leave.getStartDate() != null && leave.getEndDate() != null) {
            if (leave.getStartDate().isAfter(leave.getEndDate())) {
                throw new IllegalArgumentException("End date cannot be earlier than start date.");
            }
            int startYear = leave.getStartDate().getYear();
            int endYear = leave.getEndDate().getYear();
            if (startYear < 2000 || startYear > 2100 || endYear < 2000 || endYear > 2100) {
                throw new IllegalArgumentException("Invalid date range: Year must be between 2000 and 2100.");
            }
            long calculatedDays = java.time.temporal.ChronoUnit.DAYS.between(leave.getStartDate(), leave.getEndDate()) + 1;
            leave.setTotalDays((int) calculatedDays);
        }

        if (dto.getReason() != null) leave.setReason(dto.getReason());
        if (dto.getStatus() != null) leave.setStatus(dto.getStatus());

        LeaveRequest saved = leaveRequestRepository.save(leave);
        return mapToDto(saved);
    }

    @Transactional
    public void deleteLeaveRequest(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        LeaveRequest leave = leaveRequestRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("LeaveRequest not found"));
                
        leaveRequestRepository.delete(leave);
    }

    private LeaveRequestDto mapToDto(LeaveRequest entity) {
        return LeaveRequestDto.builder()
                .id(entity.getId())
                .employeeId(entity.getEmployee().getId())
                .employeeName(entity.getEmployee().getFirstName() + " " + entity.getEmployee().getLastName())
                .leaveType(entity.getLeaveType())
                .startDate(entity.getStartDate())
                .endDate(entity.getEndDate())
                .totalDays(entity.getTotalDays())
                .reason(entity.getReason())
                .status(entity.getStatus())
                .build();
    }
}
