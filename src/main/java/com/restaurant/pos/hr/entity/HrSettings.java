package com.restaurant.pos.hr.entity;

import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "hr_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HrSettings extends BaseEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "standard_hours_per_day", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal standardHoursPerDay = new BigDecimal("8.00");

    @Column(name = "overtime_multiplier", nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal overtimeMultiplier = new BigDecimal("1.50");

    @Column(name = "weekly_overtime_threshold", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal weeklyOvertimeThreshold = new BigDecimal("40.00");

    @Column(name = "overtime_mode", nullable = false, length = 20)
    @Builder.Default
    private String overtimeMode = "DAILY"; // DAILY, WEEKLY, BOTH

    @Column(name = "shift_day_boundary_hour", nullable = false)
    @Builder.Default
    private Integer shiftDayBoundaryHour = 4;
}
