package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.context.TimezoneResolver;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.dto.AttendanceDto;
import com.restaurant.pos.hr.dto.HrSettingsDto;
import com.restaurant.pos.hr.entity.Attendance;
import com.restaurant.pos.hr.entity.Employee;
import com.restaurant.pos.hr.entity.PunchSegment;
import com.restaurant.pos.hr.repository.AttendanceRepository;
import com.restaurant.pos.hr.repository.EmployeeRepository;
import com.restaurant.pos.hr.repository.LeaveRequestRepository;
import com.restaurant.pos.hr.repository.PunchSegmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AttendanceServiceTest {

    private AttendanceRepository attendanceRepository;
    private EmployeeRepository employeeRepository;
    private LeaveRequestRepository leaveRequestRepository;
    private PunchSegmentRepository punchSegmentRepository;
    private TimezoneResolver timezoneResolver;
    private HrSettingsService hrSettingsService;

    private AttendanceService attendanceService;

    private UUID clientId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        attendanceRepository = mock(AttendanceRepository.class);
        employeeRepository = mock(EmployeeRepository.class);
        leaveRequestRepository = mock(LeaveRequestRepository.class);
        punchSegmentRepository = mock(PunchSegmentRepository.class);
        timezoneResolver = mock(TimezoneResolver.class);
        hrSettingsService = mock(HrSettingsService.class);

        attendanceService = new AttendanceService(
                attendanceRepository,
                employeeRepository,
                leaveRequestRepository,
                punchSegmentRepository,
                timezoneResolver,
                hrSettingsService
        );

        clientId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);

        when(timezoneResolver.resolveTimezone(any(), any())).thenReturn(ZoneId.of("UTC"));
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void saveManualAttendance_Exactly12Hours_With12HourThreshold_CalculatesZeroOvertime() {
        UUID employeeId = UUID.randomUUID();
        UUID attId = UUID.randomUUID();

        Employee emp = new Employee();
        emp.setId(employeeId);
        emp.setFirstName("FRSAF");
        emp.setLastName("jkm");
        emp.setActive(true);

        Attendance att = new Attendance();
        att.setId(attId);
        att.setEmployee(emp);
        att.setAttendanceDate(LocalDate.of(2026, 9, 14));
        att.setClientId(clientId);
        att.setOrgId(orgId);

        PunchSegment seg = new PunchSegment();
        seg.setId(UUID.randomUUID());
        seg.setAttendance(att);
        seg.setSegmentType("WORK");
        seg.setClockInTime(LocalDateTime.of(2026, 9, 14, 5, 21));
        seg.setClockOutTime(LocalDateTime.of(2026, 9, 14, 17, 21));
        seg.setHoursWorked(new BigDecimal("12.00"));

        when(employeeRepository.findByIdAndClientIdAndOrgId(employeeId, clientId, orgId))
                .thenReturn(java.util.Optional.of(emp));
        when(attendanceRepository.findByIdAndClientIdAndOrgId(attId, clientId, orgId))
                .thenReturn(java.util.Optional.of(att));
        when(punchSegmentRepository.findByAttendanceId(attId)).thenReturn(List.of(seg));
        when(leaveRequestRepository.findApprovedByEmployeeIdAndDate(any(), any(), any(), any()))
                .thenReturn(List.of());

        // Configure HR settings to 12 hours standard daily working hours
        HrSettingsDto settingsDto = HrSettingsDto.builder()
                .standardHoursPerDay(new BigDecimal("12.00"))
                .overtimeMultiplier(new BigDecimal("1.50"))
                .build();
        when(hrSettingsService.getSettings()).thenReturn(settingsDto);

        com.restaurant.pos.hr.dto.AttendanceDto dto = com.restaurant.pos.hr.dto.AttendanceDto.builder()
                .id(attId)
                .employeeId(employeeId)
                .attendanceDate(LocalDate.of(2026, 9, 14))
                .clockInTime(LocalDateTime.of(2026, 9, 14, 5, 21))
                .clockOutTime(LocalDateTime.of(2026, 9, 14, 17, 21))
                .status("PRESENT")
                .punchMethod("MANUAL")
                .build();

        var result = attendanceService.saveManualAttendance(dto);

        assertThat(result).isNotNull();
        assertThat(result.getTotalHoursWorked()).isEqualByComparingTo("12.00");
        assertThat(result.getOvertimeHours()).isEqualByComparingTo("0.00");
    }

    @Test
    void saveManualAttendance_13Hours_With12HourThreshold_CalculatesOneHourOvertime() {
        UUID employeeId = UUID.randomUUID();
        UUID attId = UUID.randomUUID();

        Employee emp = new Employee();
        emp.setId(employeeId);
        emp.setFirstName("FRSAF");
        emp.setLastName("jkm");
        emp.setActive(true);

        Attendance att = new Attendance();
        att.setId(attId);
        att.setEmployee(emp);
        att.setAttendanceDate(LocalDate.of(2026, 9, 14));
        att.setClientId(clientId);
        att.setOrgId(orgId);

        PunchSegment seg = new PunchSegment();
        seg.setId(UUID.randomUUID());
        seg.setAttendance(att);
        seg.setSegmentType("WORK");
        seg.setClockInTime(LocalDateTime.of(2026, 9, 14, 5, 0));
        seg.setClockOutTime(LocalDateTime.of(2026, 9, 14, 18, 0));
        seg.setHoursWorked(new BigDecimal("13.00"));

        when(employeeRepository.findByIdAndClientIdAndOrgId(employeeId, clientId, orgId))
                .thenReturn(java.util.Optional.of(emp));
        when(attendanceRepository.findByIdAndClientIdAndOrgId(attId, clientId, orgId))
                .thenReturn(java.util.Optional.of(att));
        when(punchSegmentRepository.findByAttendanceId(attId)).thenReturn(List.of(seg));
        when(leaveRequestRepository.findApprovedByEmployeeIdAndDate(any(), any(), any(), any()))
                .thenReturn(List.of());

        // Configure HR settings to 12 hours standard daily working hours
        HrSettingsDto settingsDto = HrSettingsDto.builder()
                .standardHoursPerDay(new BigDecimal("12.00"))
                .overtimeMultiplier(new BigDecimal("1.50"))
                .build();
        when(hrSettingsService.getSettings()).thenReturn(settingsDto);

        com.restaurant.pos.hr.dto.AttendanceDto dto = com.restaurant.pos.hr.dto.AttendanceDto.builder()
                .id(attId)
                .employeeId(employeeId)
                .attendanceDate(LocalDate.of(2026, 9, 14))
                .clockInTime(LocalDateTime.of(2026, 9, 14, 5, 0))
                .clockOutTime(LocalDateTime.of(2026, 9, 14, 18, 0))
                .status("PRESENT")
                .punchMethod("MANUAL")
                .build();

        var result = attendanceService.saveManualAttendance(dto);

        assertThat(result).isNotNull();
        assertThat(result.getTotalHoursWorked()).isEqualByComparingTo("13.00");
        assertThat(result.getOvertimeHours()).isEqualByComparingTo("1.00");
    }

    @Test
    void updateHrSettings_BulkRecalculatesOvertimeForPastAttendanceRecords() {
        com.restaurant.pos.hr.repository.HrSettingsRepository hrSettingsRepository = mock(com.restaurant.pos.hr.repository.HrSettingsRepository.class);
        HrSettingsService settingsService = new HrSettingsService(hrSettingsRepository, attendanceRepository);

        Attendance pastAtt1 = new Attendance();
        pastAtt1.setId(UUID.randomUUID());
        pastAtt1.setTotalHoursWorked(new BigDecimal("10.00"));
        pastAtt1.setOvertimeHours(new BigDecimal("2.00")); // Old calculation based on 8 hr threshold

        Attendance pastAtt2 = new Attendance();
        pastAtt2.setId(UUID.randomUUID());
        pastAtt2.setTotalHoursWorked(new BigDecimal("12.00"));
        pastAtt2.setOvertimeHours(new BigDecimal("4.00")); // Old calculation based on 8 hr threshold

        when(attendanceRepository.findByClientIdAndOrgId(clientId, orgId)).thenReturn(List.of(pastAtt1, pastAtt2));
        when(hrSettingsRepository.findByClientIdAndOrgId(clientId, orgId)).thenReturn(java.util.Optional.empty());
        when(hrSettingsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        HrSettingsDto newPolicy = HrSettingsDto.builder()
                .standardHoursPerDay(new BigDecimal("10.00"))
                .build();

        settingsService.updateSettings(newPolicy);

        // pastAtt1 (10 hrs) vs 10 hr threshold -> overtimeHours should be updated to 0.00
        assertThat(pastAtt1.getOvertimeHours()).isEqualByComparingTo("0.00");
        // pastAtt2 (12 hrs) vs 10 hr threshold -> overtimeHours should be updated to 2.00
        assertThat(pastAtt2.getOvertimeHours()).isEqualByComparingTo("2.00");

        verify(attendanceRepository, times(2)).save(any(Attendance.class));
    }

    @Test
    void saveManualAttendance_ClockOutEarlierThanClockIn_ThrowsException() {
        UUID employeeId = UUID.randomUUID();
        Employee emp = new Employee();
        emp.setId(employeeId);

        when(employeeRepository.findByIdAndClientIdAndOrgId(employeeId, clientId, orgId))
                .thenReturn(java.util.Optional.of(emp));
        when(attendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AttendanceDto dto = AttendanceDto.builder()
                .employeeId(employeeId)
                .attendanceDate(LocalDate.of(2026, 9, 14))
                .clockInTime(LocalDateTime.of(2026, 9, 14, 18, 0)) // 06:00 PM
                .clockOutTime(LocalDateTime.of(2026, 9, 14, 9, 0)) // 09:00 AM (earlier)
                .status("PRESENT")
                .punchMethod("MANUAL")
                .build();

        assertThatThrownBy(() -> attendanceService.saveManualAttendance(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Clock Out time must be later than Clock In time.");
    }
}
