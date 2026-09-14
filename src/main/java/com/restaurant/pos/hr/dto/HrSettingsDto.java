package com.restaurant.pos.hr.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HrSettingsDto {
    private UUID id;
    private BigDecimal standardHoursPerDay;
    private BigDecimal overtimeMultiplier;
    private BigDecimal weeklyOvertimeThreshold;
    private String overtimeMode;
    private Integer shiftDayBoundaryHour;
}
