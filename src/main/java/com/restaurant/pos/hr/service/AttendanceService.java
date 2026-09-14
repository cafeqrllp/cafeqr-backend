package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.context.TimezoneResolver;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.dto.AttendanceDto;
import com.restaurant.pos.hr.entity.Attendance;
import com.restaurant.pos.hr.entity.Employee;
import com.restaurant.pos.hr.entity.LeaveRequest;
import com.restaurant.pos.hr.entity.PunchSegment;
import com.restaurant.pos.hr.repository.AttendanceRepository;
import com.restaurant.pos.hr.repository.EmployeeRepository;
import com.restaurant.pos.hr.repository.LeaveRequestRepository;
import com.restaurant.pos.hr.repository.PunchSegmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final PunchSegmentRepository punchSegmentRepository;
    private final TimezoneResolver timezoneResolver;
    private final HrSettingsService hrSettingsService;

    private static final BigDecimal DEFAULT_STANDARD_HOURS_PER_DAY = new BigDecimal("8.00");

    @Transactional
    public AttendanceDto clockIn(UUID employeeId, String punchMethod) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        ZoneId zoneId = timezoneResolver.resolveTimezone(clientId, orgId);
        
        Employee employee = employeeRepository.findByIdAndClientIdAndOrgId(employeeId, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        if (!employee.isActive()) {
            throw new RuntimeException("Inactive employees cannot clock in.");
        }

        LocalDateTime now = LocalDateTime.now(zoneId);
        int boundaryHour = 4;
        try {
            if (hrSettingsService != null && hrSettingsService.getSettings() != null) {
                Integer customBoundary = hrSettingsService.getSettings().getShiftDayBoundaryHour();
                if (customBoundary != null) boundaryHour = customBoundary;
            }
        } catch (Exception ignored) {}

        LocalDate shiftDate = now.toLocalDate();
        if (now.getHour() < boundaryHour) {
            shiftDate = shiftDate.minusDays(1);
        }

        // Block clock-in if employee has an approved leave today
        List<LeaveRequest> approvedLeaves = leaveRequestRepository.findApprovedByEmployeeIdAndDate(
                employeeId, shiftDate, clientId, orgId);
        if (!approvedLeaves.isEmpty()) {
            LeaveRequest leave = approvedLeaves.get(0);
            throw new RuntimeException("Cannot clock in: Employee has an approved "
                    + leave.getLeaveType() + " leave from " + leave.getStartDate()
                    + " to " + leave.getEndDate() + ". Cancel the leave first.");
        }

        Attendance attendance = attendanceRepository.findByEmployeeIdAndDateAndClientIdAndOrgId(employeeId, shiftDate, clientId, orgId)
                .orElse(null);

        if (attendance != null) {
            // Returning from break or duplicate punch?
            PunchSegment openSegment = punchSegmentRepository.findOpenSegmentByAttendanceId(attendance.getId()).orElse(null);
            if (openSegment != null) {
                throw new RuntimeException("Employee is already clocked in. Please clock out first.");
            }
            
            PunchSegment newSegment = new PunchSegment();
            newSegment.setAttendance(attendance);
            newSegment.setClockInTime(now);
            newSegment.setSegmentType("WORK");
            punchSegmentRepository.save(newSegment);
            
            recalculateAttendance(attendance);
            return mapToDto(attendanceRepository.save(attendance));
        }

        attendance = new Attendance();
        attendance.setEmployee(employee);
        attendance.setAttendanceDate(shiftDate);
        attendance.setClockInTime(now); // Backward compatibility
        attendance.setPunchMethod(punchMethod);
        attendance.setStatus("PRESENT");
        
        Attendance saved = attendanceRepository.save(attendance);
        
        PunchSegment firstSegment = new PunchSegment();
        firstSegment.setAttendance(saved);
        firstSegment.setClockInTime(now);
        firstSegment.setSegmentType("WORK");
        punchSegmentRepository.save(firstSegment);
        
        recalculateAttendance(saved);
        return mapToDto(attendanceRepository.save(saved));
    }

    @Transactional
    public AttendanceDto clockOut(UUID employeeId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        ZoneId zoneId = timezoneResolver.resolveTimezone(clientId, orgId);
        
        List<Attendance> activeShifts = attendanceRepository.findActiveAttendanceByEmployeeIdAndClientIdAndOrgId(employeeId, clientId, orgId);
        if (activeShifts.isEmpty()) {
            throw new RuntimeException("No active shift found for employee.");
        }

        Attendance attendance = activeShifts.get(0);
        PunchSegment openSegment = punchSegmentRepository.findOpenSegmentByAttendanceId(attendance.getId())
                .orElseThrow(() -> new RuntimeException("No active open punch segment found to clock out."));

        LocalDateTime now = LocalDateTime.now(zoneId);
        openSegment.setClockOutTime(now);
        
        Duration duration = Duration.between(openSegment.getClockInTime(), now);
        double hours = duration.toMinutes() / 60.0;
        openSegment.setHoursWorked(BigDecimal.valueOf(hours).setScale(2, RoundingMode.HALF_UP));
        
        punchSegmentRepository.save(openSegment);
        
        attendance.setClockOutTime(now); // Backward compatibility
        
        recalculateAttendance(attendance);
        return mapToDto(attendanceRepository.save(attendance));
    }

    private void recalculateAttendance(Attendance attendance) {
        List<PunchSegment> segments = punchSegmentRepository.findByAttendanceId(attendance.getId());
        
        BigDecimal totalWorked = BigDecimal.ZERO;
        LocalDateTime firstIn = null;
        LocalDateTime lastOut = null;
        
        for (PunchSegment seg : segments) {
            if ("WORK".equals(seg.getSegmentType())) {
                BigDecimal h = seg.getHoursWorked() != null ? seg.getHoursWorked() : BigDecimal.ZERO;
                if (seg.getClockOutTime() == null && seg.getClockInTime() != null) {
                    // Open segment, calculate up to now
                    ZoneId zoneId = timezoneResolver.resolveTimezone(attendance.getClientId(), attendance.getOrgId());
                    Duration dur = Duration.between(seg.getClockInTime(), LocalDateTime.now(zoneId));
                    h = BigDecimal.valueOf(dur.toMinutes() / 60.0).setScale(2, RoundingMode.HALF_UP);
                }
                totalWorked = totalWorked.add(h);
            }
            if (firstIn == null || (seg.getClockInTime() != null && seg.getClockInTime().isBefore(firstIn))) {
                firstIn = seg.getClockInTime();
            }
            if (lastOut == null || (seg.getClockOutTime() != null && seg.getClockOutTime().isAfter(lastOut))) {
                lastOut = seg.getClockOutTime();
            }
        }
        
        attendance.setTotalHoursWorked(totalWorked);
        attendance.setClockInTime(firstIn);
        attendance.setClockOutTime(lastOut);

        BigDecimal threshold = DEFAULT_STANDARD_HOURS_PER_DAY;
        try {
            if (hrSettingsService != null && hrSettingsService.getSettings() != null) {
                BigDecimal customHours = hrSettingsService.getSettings().getStandardHoursPerDay();
                if (customHours != null && customHours.compareTo(BigDecimal.ZERO) > 0) {
                    threshold = customHours;
                }
            }
        } catch (Exception ignored) {}
        
        if (totalWorked.compareTo(threshold) > 0) {
            attendance.setOvertimeHours(totalWorked.subtract(threshold));
        } else {
            attendance.setOvertimeHours(BigDecimal.ZERO);
        }
    }

    @Transactional(readOnly = true)
    public String getEmployeeCurrentStatus(UUID employeeId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        List<Attendance> activeShifts = attendanceRepository.findActiveAttendanceByEmployeeIdAndClientIdAndOrgId(employeeId, clientId, orgId);
        if (activeShifts.isEmpty()) {
            return "NOT_CLOCKED_IN";
        }
        
        Attendance shift = activeShifts.get(0);
        java.util.Optional<PunchSegment> openSegment = punchSegmentRepository.findOpenSegmentByAttendanceId(shift.getId());
        if (openSegment.isPresent()) {
            return "WORKING";
        } else {
            return "ON_BREAK";
        }
    }

    @Transactional(readOnly = true)
    public List<AttendanceDto> getAllAttendanceRecords(LocalDate startDate, LocalDate endDate) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        ZoneId zoneId = timezoneResolver.resolveTimezone(clientId, orgId);

        LocalDate start = (startDate != null) ? startDate : LocalDate.now(zoneId).minusDays(30);
        LocalDate end = (endDate != null) ? endDate : LocalDate.now(zoneId);

        return attendanceRepository.findAllByDateRangeAndClientIdAndOrgId(start, end, clientId, orgId)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AttendanceDto> getAttendanceByEmployeeAndDateRange(UUID employeeId, LocalDate startDate, LocalDate endDate) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        return attendanceRepository.findByEmployeeIdAndDateRangeAndClientIdAndOrgId(employeeId, startDate, endDate, clientId, orgId)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public AttendanceDto saveManualAttendance(AttendanceDto dto) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        ZoneId zoneId = timezoneResolver.resolveTimezone(clientId, orgId);

        Attendance attendance;
        LocalDate attDate = dto.getAttendanceDate() != null ? dto.getAttendanceDate() : LocalDate.now(zoneId);

        if (dto.getId() != null) {
            attendance = attendanceRepository.findByIdAndClientIdAndOrgId(dto.getId(), clientId, orgId)
                    .orElseThrow(() -> new RuntimeException("Attendance record not found"));
        } else {
            // Guard against duplicates
            if (attendanceRepository.findByEmployeeIdAndDateAndClientIdAndOrgId(dto.getEmployeeId(), attDate, clientId, orgId).isPresent()) {
                throw new RuntimeException("A timecard already exists for this employee on this date.");
            }
            attendance = new Attendance();
        }

        Employee employee = employeeRepository.findByIdAndClientIdAndOrgId(dto.getEmployeeId(), clientId, orgId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        attendance.setEmployee(employee);
        attendance.setAttendanceDate(attDate);
        attendance.setStatus(dto.getStatus() != null ? dto.getStatus() : "PRESENT");
        attendance.setPunchMethod(dto.getPunchMethod() != null ? dto.getPunchMethod() : "MANUAL");

        if ("ABSENT".equalsIgnoreCase(attendance.getStatus())) {
            attendance.setClockInTime(null);
            attendance.setClockOutTime(null);
            attendance.setTotalHoursWorked(BigDecimal.ZERO);
            attendance.setOvertimeHours(BigDecimal.ZERO);
            attendanceRepository.save(attendance);
            // Delete any existing segments
            if (attendance.getId() != null) {
                List<PunchSegment> segments = punchSegmentRepository.findByAttendanceId(attendance.getId());
                punchSegmentRepository.deleteAll(segments);
            }
        } else {
            // Block manual attendance if employee has an approved leave on that date
            List<LeaveRequest> approvedLeaves = leaveRequestRepository.findApprovedByEmployeeIdAndDate(
                    dto.getEmployeeId(), attendance.getAttendanceDate(), clientId, orgId);
            if (!approvedLeaves.isEmpty()) {
                LeaveRequest leave = approvedLeaves.get(0);
                throw new RuntimeException("Cannot create attendance: Employee has an approved "
                        + leave.getLeaveType() + " leave from " + leave.getStartDate()
                        + " to " + leave.getEndDate() + ". Cancel the leave first.");
            }
            
            Attendance savedAtt = attendanceRepository.save(attendance);

            // Manual timecard creates a single WORK segment from the payload
            LocalDateTime clockIn = dto.getClockInTime();
            if (clockIn != null && attendance.getAttendanceDate() != null) {
                clockIn = LocalDateTime.of(attendance.getAttendanceDate(), clockIn.toLocalTime());
            }

            LocalDateTime clockOut = dto.getClockOutTime();
            if (clockOut != null && attendance.getAttendanceDate() != null) {
                clockOut = LocalDateTime.of(attendance.getAttendanceDate(), clockOut.toLocalTime());
                if (clockIn != null && clockOut.isBefore(clockIn)) {
                    clockOut = clockOut.plusDays(1);
                }
            }
            
            List<PunchSegment> existingSegs = punchSegmentRepository.findByAttendanceId(savedAtt.getId());
            PunchSegment segment;
            if (existingSegs.isEmpty()) {
                segment = new PunchSegment();
                segment.setAttendance(savedAtt);
            } else {
                // Just update the first one if editing simple timecard
                segment = existingSegs.get(0);
            }
            
            segment.setSegmentType("WORK");
            segment.setClockInTime(clockIn);
            segment.setClockOutTime(clockOut);
            if (clockIn != null && clockOut != null) {
                Duration dur = Duration.between(clockIn, clockOut);
                segment.setHoursWorked(BigDecimal.valueOf(dur.toMinutes() / 60.0).setScale(2, RoundingMode.HALF_UP));
            } else {
                segment.setHoursWorked(BigDecimal.ZERO);
            }
            punchSegmentRepository.save(segment);
            
            recalculateAttendance(savedAtt);
            attendance = attendanceRepository.save(savedAtt);
        }

        Attendance saved = attendanceRepository.save(attendance);
        return mapToDto(saved);
    }

    @Transactional
    public void deleteAttendanceRecord(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        Attendance attendance = attendanceRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("Attendance record not found"));

        attendanceRepository.delete(attendance);
    }

    private AttendanceDto mapToDto(Attendance entity) {
        List<PunchSegment> segs = entity.getId() != null ? punchSegmentRepository.findByAttendanceId(entity.getId()) : java.util.Collections.emptyList();
        
        BigDecimal totalBreak = BigDecimal.ZERO;
        LocalDateTime prevOut = null;
        for (PunchSegment seg : segs) {
            if (prevOut != null && seg.getClockInTime() != null) {
                Duration gap = Duration.between(prevOut, seg.getClockInTime());
                if (!gap.isNegative()) {
                    totalBreak = totalBreak.add(BigDecimal.valueOf(gap.toMinutes() / 60.0));
                }
            }
            prevOut = seg.getClockOutTime();
        }

        List<com.restaurant.pos.hr.dto.PunchSegmentDto> segDtos = segs.stream().map(s -> com.restaurant.pos.hr.dto.PunchSegmentDto.builder()
                .id(s.getId())
                .attendanceId(s.getAttendance().getId())
                .clockInTime(s.getClockInTime())
                .clockOutTime(s.getClockOutTime())
                .hoursWorked(s.getHoursWorked())
                .segmentType(s.getSegmentType())
                .build()).collect(Collectors.toList());

        return AttendanceDto.builder()
                .id(entity.getId())
                .employeeId(entity.getEmployee().getId())
                .employeeName(entity.getEmployee().getFirstName() + " " + entity.getEmployee().getLastName())
                .attendanceDate(entity.getAttendanceDate())
                .clockInTime(entity.getClockInTime())
                .clockOutTime(entity.getClockOutTime())
                .totalHoursWorked(entity.getTotalHoursWorked())
                .overtimeHours(entity.getOvertimeHours())
                .status(entity.getStatus())
                .punchMethod(entity.getPunchMethod())
                .totalBreakHours(totalBreak.setScale(2, RoundingMode.HALF_UP))
                .segments(segDtos)
                .build();
    }
}
