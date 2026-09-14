package com.restaurant.pos.hr.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceDto {
    private UUID id;
    private UUID employeeId;
    private String employeeName;
    private LocalDate attendanceDate;
    private LocalDateTime clockInTime;
    private LocalDateTime clockOutTime;
    private BigDecimal totalHoursWorked;
    private BigDecimal overtimeHours;
    private String status;
    private String punchMethod;
    private BigDecimal totalBreakHours;
    private java.util.List<PunchSegmentDto> segments;
}
