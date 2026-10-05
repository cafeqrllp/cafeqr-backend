package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.service.AuditLogService;
import com.restaurant.pos.hr.dto.HrSettingsDto;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.dto.PayrollRunDto;
import com.restaurant.pos.hr.dto.SalarySlipDto;
import com.restaurant.pos.hr.entity.*;
import com.restaurant.pos.hr.repository.*;
import com.restaurant.pos.expense.domain.Expense;
import com.restaurant.pos.expense.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PayrollEngineService {

    private final PayrollRunRepository payrollRunRepository;
    private final SalarySlipRepository salarySlipRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeSalaryComponentRepository employeeSalaryComponentRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final SalaryAdvanceRepository salaryAdvanceRepository;
    private final HrSettingsService hrSettingsService;
    private final ExpenseRepository expenseRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public PayrollRunDto initiatePayrollRun(PayrollRunDto dto) {
        if (dto == null) {
            throw new BusinessException("Payroll run data is required.");
        }
        if (dto.getName() == null || dto.getName().trim().isEmpty()) {
            throw new BusinessException("Payroll run name cannot be empty.");
        }
        if (dto.getStartDate() == null || dto.getEndDate() == null) {
            throw new BusinessException("Start date and end date are required.");
        }
        if (dto.getStartDate().isAfter(dto.getEndDate())) {
            throw new BusinessException("Start date cannot be after end date.");
        }
        int startYear = dto.getStartDate().getYear();
        int endYear = dto.getEndDate().getYear();
        if (startYear < 2000 || startYear > 2100 || endYear < 2000 || endYear > 2100) {
            throw new BusinessException("Invalid date range: Year must be between 2000 and 2100.");
        }

        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        if (payrollRunRepository.existsByNameAndClientIdAndOrgId(dto.getName().trim(), clientId, orgId)) {
            throw new BusinessException("A payroll run with the name '" + dto.getName().trim() + "' already exists.");
        }

        List<PayrollRun> overlappingRuns = payrollRunRepository.findOverlappingRuns(clientId, orgId, dto.getStartDate(), dto.getEndDate());
        if (!overlappingRuns.isEmpty()) {
            PayrollRun existing = overlappingRuns.get(0);
            throw new BusinessException("Payroll has already been processed (or is currently processing) for an overlapping period (" 
                    + existing.getStartDate() + " to " + existing.getEndDate() + " - Run: '" + existing.getName() + "'). Duplicate payroll runs for an already completed period are not allowed.");
        }

        PayrollRun run = new PayrollRun();
        run.setName(dto.getName().trim());
        run.setStartDate(dto.getStartDate());
        run.setEndDate(dto.getEndDate());
        run.setStatus("PROCESSING");
        
        PayrollRun saved = payrollRunRepository.save(run);
        
        // Generate Slips
        generateSalarySlips(saved);
        
        saved.setStatus("COMPLETED");
        return mapToRunDto(payrollRunRepository.save(saved));
    }

    private void generateSalarySlips(PayrollRun run) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        // Calculate period duration in days and months factor
        long daysInPeriod = java.time.temporal.ChronoUnit.DAYS.between(run.getStartDate(), run.getEndDate()) + 1;
        if (daysInPeriod <= 0) daysInPeriod = 1;
        
        // Months factor (30 days = 1.0 month)
        BigDecimal monthsInPeriod = new BigDecimal(daysInPeriod).divide(new BigDecimal("30"), 4, RoundingMode.HALF_UP);

        HrSettingsDto hrSettings = hrSettingsService != null ? hrSettingsService.getSettings() : null;

        // 1. Fetch all active employees
        List<Employee> employees = employeeRepository.findByClientIdAndOrgId(clientId, orgId)
                .stream().filter(Employee::isActive).collect(Collectors.toList());

        for (Employee emp : employees) {
            SalarySlip slip = new SalarySlip();
            slip.setEmployee(emp);
            slip.setPayrollRun(run);
            
            // 3. Aggregate Timecards
            List<Attendance> attendances = attendanceRepository.findByEmployeeIdAndDateRangeAndClientIdAndOrgId(
                    emp.getId(), run.getStartDate(), run.getEndDate(), clientId, orgId);
            
            BigDecimal totalWorkedHours = attendances.stream()
                    .map(a -> a.getTotalHoursWorked() != null ? a.getTotalHoursWorked() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal overtimeHours = calculateOvertimeHours(attendances, hrSettings);
            BigDecimal normalHours = totalWorkedHours.subtract(overtimeHours);
            if (normalHours.compareTo(BigDecimal.ZERO) < 0) {
                normalHours = BigDecimal.ZERO;
            }
                    
            // 4. Aggregate Leaves
            List<LeaveRequest> leaves = leaveRequestRepository.findApprovedByEmployeeIdAndDateRange(
                    emp.getId(), run.getStartDate(), run.getEndDate(), clientId, orgId);
                    
            int unpaidLeaveDays = leaves.stream()
                    .filter(l -> "UNPAID".equals(l.getLeaveType()))
                    .mapToInt(LeaveRequest::getTotalDays)
                    .sum();
            slip.setTotalUnpaidLeaveDays(unpaidLeaveDays);

            // Safety net: for Hourly employees, exclude attendance on unpaid leave dates
            if ("HOURLY".equals(emp.getEmploymentType()) && unpaidLeaveDays > 0) {
                Set<LocalDate> unpaidLeaveDates = new HashSet<>();
                for (LeaveRequest lr : leaves) {
                    if ("UNPAID".equals(lr.getLeaveType())) {
                        LocalDate d = lr.getStartDate();
                        while (!d.isAfter(lr.getEndDate())) {
                            unpaidLeaveDates.add(d);
                            d = d.plusDays(1);
                        }
                    }
                }
                List<Attendance> filtered = attendances.stream()
                        .filter(a -> !unpaidLeaveDates.contains(a.getAttendanceDate()))
                        .collect(Collectors.toList());

                totalWorkedHours = filtered.stream()
                        .map(a -> a.getTotalHoursWorked() != null ? a.getTotalHoursWorked() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                overtimeHours = calculateOvertimeHours(filtered, hrSettings);
                normalHours = totalWorkedHours.subtract(overtimeHours);
                if (normalHours.compareTo(BigDecimal.ZERO) < 0) {
                    normalHours = BigDecimal.ZERO;
                }
            }


            if ("HOURLY".equals(emp.getEmploymentType())) {
                int paidLeaveDays = leaves.stream()
                        .filter(l -> "PAID".equals(l.getLeaveType()) || "SICK".equals(l.getLeaveType()))
                        .mapToInt(LeaveRequest::getTotalDays)
                        .sum();
                if (paidLeaveDays > 0) {
                    BigDecimal standardHoursPerDay = new BigDecimal("8.00");
                    try {
                        if (hrSettingsService != null && hrSettingsService.getSettings() != null) {
                            BigDecimal customHours = hrSettingsService.getSettings().getStandardHoursPerDay();
                            if (customHours != null && customHours.compareTo(BigDecimal.ZERO) > 0) {
                                standardHoursPerDay = customHours;
                            }
                        }
                    } catch (Exception ignored) {}
                    normalHours = normalHours.add(standardHoursPerDay.multiply(BigDecimal.valueOf(paidLeaveDays)));
                }
            }

            BigDecimal totalHours = normalHours.add(overtimeHours);
            slip.setTotalWorkedHours(totalHours);
            slip.setRegularHours(normalHours);
            slip.setOvertimeHours(overtimeHours);

            // 5. Calculate Base Pay
            BigDecimal grossPay = BigDecimal.ZERO;
            BigDecimal unpaidLeaveDeductionAmount = BigDecimal.ZERO;
            BigDecimal computedOtPay = BigDecimal.ZERO;

            if ("HOURLY".equals(emp.getEmploymentType())) {
                BigDecimal otMultiplier = new BigDecimal("1.50");
                try {
                    if (hrSettingsService != null && hrSettingsService.getSettings() != null) {
                        BigDecimal customMult = hrSettingsService.getSettings().getOvertimeMultiplier();
                        if (customMult != null && customMult.compareTo(BigDecimal.ONE) >= 0) {
                            otMultiplier = customMult;
                        }
                    }
                } catch (Exception ignored) {
                }

                BigDecimal normalPay = emp.getHourlyRate().multiply(normalHours);
                computedOtPay = emp.getHourlyRate().multiply(otMultiplier).multiply(overtimeHours).setScale(2, RoundingMode.HALF_UP);
                grossPay = normalPay.add(computedOtPay);
            } else {
                // Monthly salaried - calculate base pay proportional to the date range (daysInPeriod / 30)
                BigDecimal dailyRate = emp.getBaseSalary().divide(new BigDecimal("30"), 4, RoundingMode.HALF_UP);
                BigDecimal basePayForPeriod = dailyRate.multiply(new BigDecimal(daysInPeriod)).setScale(2, RoundingMode.HALF_UP);
                unpaidLeaveDeductionAmount = dailyRate.multiply(BigDecimal.valueOf(unpaidLeaveDays)).setScale(2, RoundingMode.HALF_UP);
                grossPay = basePayForPeriod; // Exception-Based Pay: Gross pay is full, unpaid leave is a deduction

                // CQR-135: Include overtime pay for monthly-salaried employees who worked beyond the standard daily threshold.
                // Effective hourly rate = baseSalary / 30 days / standardHoursPerDay
                if (overtimeHours.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal standardHoursPerDay = new BigDecimal("8.00");
                    BigDecimal otMultiplier = new BigDecimal("1.50");
                    try {
                        if (hrSettingsService != null && hrSettingsService.getSettings() != null) {
                            BigDecimal customHours = hrSettingsService.getSettings().getStandardHoursPerDay();
                            if (customHours != null && customHours.compareTo(BigDecimal.ZERO) > 0) {
                                standardHoursPerDay = customHours;
                            }
                            BigDecimal customMult = hrSettingsService.getSettings().getOvertimeMultiplier();
                            if (customMult != null && customMult.compareTo(BigDecimal.ONE) >= 0) {
                                otMultiplier = customMult;
                            }
                        }
                    } catch (Exception ignored) {}
                    BigDecimal effectiveHourlyRate = dailyRate.divide(standardHoursPerDay, 4, RoundingMode.HALF_UP);
                    computedOtPay = effectiveHourlyRate.multiply(otMultiplier).multiply(overtimeHours).setScale(2, RoundingMode.HALF_UP);
                    grossPay = grossPay.add(computedOtPay);
                }
            }
            slip.setOvertimePay(computedOtPay);

            // 6. Apply Rules Engine (Employee Specific Components)
            BigDecimal totalDeductions = BigDecimal.ZERO.add(unpaidLeaveDeductionAmount);
            List<EmployeeSalaryComponent> empComponents = employeeSalaryComponentRepository.findActiveByEmployeeId(emp.getId());
            
            for (EmployeeSalaryComponent empComp : empComponents) {
                SalaryComponent comp = empComp.getSalaryComponent();
                BigDecimal compAmount = BigDecimal.ZERO;
                
                if ("FIXED".equals(comp.getAmountType())) {
                    BigDecimal fixedBase = empComp.getOverrideAmount() != null ? empComp.getOverrideAmount() : comp.getDefaultAmount();
                    if (fixedBase == null) fixedBase = BigDecimal.ZERO;
                    compAmount = fixedBase.multiply(monthsInPeriod).setScale(2, RoundingMode.HALF_UP);
                } else if ("PERCENTAGE".equals(comp.getAmountType())) {
                    BigDecimal percentage = empComp.getOverridePercentage() != null ? empComp.getOverridePercentage() : comp.getPercentage();
                    if (percentage == null) percentage = BigDecimal.ZERO;
                    compAmount = grossPay.multiply(percentage).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                }

                if ("EARNING".equals(comp.getType())) {
                    grossPay = grossPay.add(compAmount);
                } else if ("DEDUCTION".equals(comp.getType())) {
                    totalDeductions = totalDeductions.add(compAmount);
                }
            }

            // 7. Deduct Salary Advances
            List<SalaryAdvance> advances = salaryAdvanceRepository.findActiveAdvancesByEmployeeId(emp.getId(), clientId, orgId);
            for (SalaryAdvance advance : advances) {
                BigDecimal monthlyInstallment = advance.getMonthlyInstallmentAmount();
                BigDecimal toDeduct = monthlyInstallment.multiply(monthsInPeriod).setScale(2, RoundingMode.HALF_UP);
                if (toDeduct.compareTo(advance.getRemainingBalance()) > 0) {
                    toDeduct = advance.getRemainingBalance();
                }
                totalDeductions = totalDeductions.add(toDeduct);
                
                // Update advance balance
                advance.setRemainingBalance(advance.getRemainingBalance().subtract(toDeduct));
                if (advance.getRemainingBalance().compareTo(BigDecimal.ZERO) <= 0) {
                    advance.setRemainingBalance(BigDecimal.ZERO);
                    advance.setStatus("PAID");
                }
                salaryAdvanceRepository.save(advance);
            }

            // 8. Finalize Net Pay
            slip.setGrossPay(grossPay);
            slip.setTotalDeductions(totalDeductions);
            
            BigDecimal netPay = grossPay.subtract(totalDeductions);
            if (netPay.compareTo(BigDecimal.ZERO) < 0) netPay = BigDecimal.ZERO;
            
            slip.setNetPay(netPay);
            slip.setStatus("GENERATED");
            
            salarySlipRepository.save(slip);
        }
    }

    @Transactional(readOnly = true)
    public List<PayrollRunDto> getAllPayrollRuns() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        return payrollRunRepository.findByClientIdAndOrgId(clientId, orgId).stream()
                .map(this::mapToRunDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SalarySlipDto> getSlipsForRun(UUID payrollRunId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        return salarySlipRepository.findByPayrollRunIdAndClientIdAndOrgId(payrollRunId, clientId, orgId).stream()
                .map(this::mapToSlipDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deletePayrollRun(UUID payrollRunId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        PayrollRun run = payrollRunRepository.findByIdAndClientIdAndOrgId(payrollRunId, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("PayrollRun not found"));

        List<SalarySlip> slips = salarySlipRepository.findByPayrollRunIdAndClientIdAndOrgId(payrollRunId, clientId, orgId);
        salarySlipRepository.deleteAll(slips);
        
        String expenseNo = "PR-" + run.getId().toString().substring(0, 8).toUpperCase();
        List<Expense> expenses = expenseRepository.findByClientIdAndOrgIdAndExpenseNo(clientId, orgId, expenseNo);
        if (!expenses.isEmpty()) {
            expenseRepository.deleteAll(expenses);
        }

        payrollRunRepository.delete(run);

        if (auditLogService != null) {
            auditLogService.logAction("DELETE_PAYROLL_RUN", "PayrollRun", payrollRunId.toString());
        }
    }

    public BigDecimal calculateOvertimeHours(List<Attendance> attendances, HrSettingsDto settings) {
        if (attendances == null || attendances.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal dailyOvertimeTotal = attendances.stream()
                .map(a -> a.getOvertimeHours() != null ? a.getOvertimeHours() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String mode = settings != null && settings.getOvertimeMode() != null 
                ? settings.getOvertimeMode().toUpperCase() 
                : "DAILY";

        if ("DAILY".equals(mode)) {
            return dailyOvertimeTotal;
        }

        BigDecimal weeklyThreshold = (settings != null && settings.getWeeklyOvertimeThreshold() != null)
                ? settings.getWeeklyOvertimeThreshold()
                : new BigDecimal("40.00");

        java.time.temporal.WeekFields weekFields = java.time.temporal.WeekFields.of(java.time.DayOfWeek.MONDAY, 1);
        Map<String, BigDecimal> weeklyHoursMap = new HashMap<>();

        for (Attendance a : attendances) {
            if (a.getAttendanceDate() != null && a.getTotalHoursWorked() != null) {
                int year = a.getAttendanceDate().get(weekFields.weekBasedYear());
                int week = a.getAttendanceDate().get(weekFields.weekOfWeekBasedYear());
                String weekKey = year + "-W" + week;

                weeklyHoursMap.put(weekKey, weeklyHoursMap.getOrDefault(weekKey, BigDecimal.ZERO).add(a.getTotalHoursWorked()));
            }
        }

        BigDecimal weeklyOvertimeTotal = BigDecimal.ZERO;
        for (BigDecimal weeklyHours : weeklyHoursMap.values()) {
            if (weeklyHours.compareTo(weeklyThreshold) > 0) {
                weeklyOvertimeTotal = weeklyOvertimeTotal.add(weeklyHours.subtract(weeklyThreshold));
            }
        }

        if ("WEEKLY".equals(mode)) {
            return weeklyOvertimeTotal;
        } else if ("BOTH".equals(mode)) {
            return dailyOvertimeTotal.max(weeklyOvertimeTotal);
        }

        return dailyOvertimeTotal;
    }


    private PayrollRunDto mapToRunDto(PayrollRun entity) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        List<SalarySlip> slips = salarySlipRepository.findByPayrollRunIdAndClientIdAndOrgId(entity.getId(), clientId, orgId);
        BigDecimal totalAmount = slips.stream()
                .map(s -> s.getNetPay() != null ? s.getNetPay() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return PayrollRunDto.builder()
                .id(entity.getId())
                .name(entity.getName())
                .startDate(entity.getStartDate())
                .endDate(entity.getEndDate())
                .status(entity.getStatus())
                .totalAmount(totalAmount)
                .build();
    }
    
    private SalarySlipDto mapToSlipDto(SalarySlip entity) {
        BigDecimal totalWorked = entity.getTotalWorkedHours() != null ? entity.getTotalWorkedHours() : BigDecimal.ZERO;
        BigDecimal otHours = entity.getOvertimeHours() != null ? entity.getOvertimeHours() : BigDecimal.ZERO;
        BigDecimal regHours = entity.getRegularHours() != null ? entity.getRegularHours() : totalWorked.subtract(otHours).max(BigDecimal.ZERO);
        BigDecimal otPay = entity.getOvertimePay() != null ? entity.getOvertimePay() : BigDecimal.ZERO;

        return SalarySlipDto.builder()
                .id(entity.getId())
                .employeeId(entity.getEmployee().getId())
                .employeeName(entity.getEmployee().getFirstName() + " " + (entity.getEmployee().getLastName() != null ? entity.getEmployee().getLastName() : ""))
                .payrollRunId(entity.getPayrollRun().getId())
                .totalWorkedHours(totalWorked)
                .regularHours(regHours)
                .overtimeHours(otHours)
                .overtimePay(otPay)
                .totalUnpaidLeaveDays(entity.getTotalUnpaidLeaveDays())
                .grossPay(entity.getGrossPay())
                .totalDeductions(entity.getTotalDeductions())
                .netPay(entity.getNetPay())
                .status(entity.getStatus())
                .build();
    }
}
