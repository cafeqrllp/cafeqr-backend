package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.dto.HrSettingsDto;
import com.restaurant.pos.hr.entity.HrSettings;
import com.restaurant.pos.hr.repository.HrSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HrSettingsService {

    private final HrSettingsRepository hrSettingsRepository;

    private static final BigDecimal DEFAULT_STANDARD_HOURS = new BigDecimal("8.00");
    private static final BigDecimal DEFAULT_OVERTIME_MULTIPLIER = new BigDecimal("1.50");
    private static final BigDecimal DEFAULT_WEEKLY_THRESHOLD = new BigDecimal("40.00");
    private static final String DEFAULT_OVERTIME_MODE = "DAILY";
    private static final Integer DEFAULT_SHIFT_DAY_BOUNDARY_HOUR = 4;

    @Transactional(readOnly = true)
    public HrSettingsDto getSettings() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        return hrSettingsRepository.findByClientIdAndOrgId(clientId, orgId)
                .map(this::mapToDto)
                .orElseGet(() -> HrSettingsDto.builder()
                        .standardHoursPerDay(DEFAULT_STANDARD_HOURS)
                        .overtimeMultiplier(DEFAULT_OVERTIME_MULTIPLIER)
                        .weeklyOvertimeThreshold(DEFAULT_WEEKLY_THRESHOLD)
                        .overtimeMode(DEFAULT_OVERTIME_MODE)
                        .shiftDayBoundaryHour(DEFAULT_SHIFT_DAY_BOUNDARY_HOUR)
                        .build());
    }

    @Transactional(readOnly = true)
    public HrSettings getSettingsEntity() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        return hrSettingsRepository.findByClientIdAndOrgId(clientId, orgId)
                .orElseGet(() -> HrSettings.builder()
                        .standardHoursPerDay(DEFAULT_STANDARD_HOURS)
                        .overtimeMultiplier(DEFAULT_OVERTIME_MULTIPLIER)
                        .weeklyOvertimeThreshold(DEFAULT_WEEKLY_THRESHOLD)
                        .overtimeMode(DEFAULT_OVERTIME_MODE)
                        .shiftDayBoundaryHour(DEFAULT_SHIFT_DAY_BOUNDARY_HOUR)
                        .build());
    }

    @Transactional
    public HrSettingsDto updateSettings(HrSettingsDto dto) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        HrSettings settings = hrSettingsRepository.findByClientIdAndOrgId(clientId, orgId)
                .orElseGet(() -> {
                    HrSettings newSettings = new HrSettings();
                    newSettings.setClientId(clientId);
                    newSettings.setOrgId(orgId);
                    return newSettings;
                });

        if (dto.getStandardHoursPerDay() != null && dto.getStandardHoursPerDay().compareTo(BigDecimal.ZERO) > 0) {
            settings.setStandardHoursPerDay(dto.getStandardHoursPerDay());
        } else if (settings.getStandardHoursPerDay() == null) {
            settings.setStandardHoursPerDay(DEFAULT_STANDARD_HOURS);
        }

        if (dto.getOvertimeMultiplier() != null && dto.getOvertimeMultiplier().compareTo(BigDecimal.ONE) >= 0) {
            settings.setOvertimeMultiplier(dto.getOvertimeMultiplier());
        } else if (settings.getOvertimeMultiplier() == null) {
            settings.setOvertimeMultiplier(DEFAULT_OVERTIME_MULTIPLIER);
        }

        if (dto.getWeeklyOvertimeThreshold() != null && dto.getWeeklyOvertimeThreshold().compareTo(BigDecimal.ZERO) > 0) {
            settings.setWeeklyOvertimeThreshold(dto.getWeeklyOvertimeThreshold());
        } else if (settings.getWeeklyOvertimeThreshold() == null) {
            settings.setWeeklyOvertimeThreshold(DEFAULT_WEEKLY_THRESHOLD);
        }

        if (dto.getOvertimeMode() != null && !dto.getOvertimeMode().trim().isEmpty()) {
            settings.setOvertimeMode(dto.getOvertimeMode().trim().toUpperCase());
        } else if (settings.getOvertimeMode() == null) {
            settings.setOvertimeMode(DEFAULT_OVERTIME_MODE);
        }

        if (dto.getShiftDayBoundaryHour() != null && dto.getShiftDayBoundaryHour() >= 0 && dto.getShiftDayBoundaryHour() <= 23) {
            settings.setShiftDayBoundaryHour(dto.getShiftDayBoundaryHour());
        } else if (settings.getShiftDayBoundaryHour() == null) {
            settings.setShiftDayBoundaryHour(DEFAULT_SHIFT_DAY_BOUNDARY_HOUR);
        }

        HrSettings saved = hrSettingsRepository.save(settings);
        return mapToDto(saved);
    }

    private HrSettingsDto mapToDto(HrSettings entity) {
        return HrSettingsDto.builder()
                .id(entity.getId())
                .standardHoursPerDay(entity.getStandardHoursPerDay() != null ? entity.getStandardHoursPerDay() : DEFAULT_STANDARD_HOURS)
                .overtimeMultiplier(entity.getOvertimeMultiplier() != null ? entity.getOvertimeMultiplier() : DEFAULT_OVERTIME_MULTIPLIER)
                .weeklyOvertimeThreshold(entity.getWeeklyOvertimeThreshold() != null ? entity.getWeeklyOvertimeThreshold() : DEFAULT_WEEKLY_THRESHOLD)
                .overtimeMode(entity.getOvertimeMode() != null ? entity.getOvertimeMode() : DEFAULT_OVERTIME_MODE)
                .shiftDayBoundaryHour(entity.getShiftDayBoundaryHour() != null ? entity.getShiftDayBoundaryHour() : DEFAULT_SHIFT_DAY_BOUNDARY_HOUR)
                .build();
    }
}
