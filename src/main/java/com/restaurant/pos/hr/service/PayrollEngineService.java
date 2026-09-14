package com.restaurant.pos.hr.service;

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
import java.util.List;
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

    @Transactional
    public PayrollRunDto initiatePayrollRun(PayrollRunDto dto) {
        PayrollRun run = new PayrollRun();
        run.setName(dto.getName());
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
            
            BigDecimal normalHours = attendances.stream()
                    .map(a -> {
                        BigDecimal total = a.getTotalHoursWorked() != null ? a.getTotalHoursWorked() : BigDecimal.ZERO;
                        BigDecimal ot = a.getOvertimeHours() != null ? a.getOvertimeHours() : BigDecimal.ZERO;
                        return total.subtract(ot);
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                    
            BigDecimal overtimeHours = attendances.stream()
                    .map(a -> a.getOvertimeHours() != null ? a.getOvertimeHours() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                    
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
                java.util.Set<LocalDate> unpaidLeaveDates = new java.util.HashSet<>();
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
                // Recalculate hours from filtered list only
                normalHours = filtered.stream()
                        .map(a -> {
                            BigDecimal total = a.getTotalHoursWorked() != null ? a.getTotalHoursWorked() : BigDecimal.ZERO;
                            BigDecimal ot = a.getOvertimeHours() != null ? a.getOvertimeHours() : BigDecimal.ZERO;
                            return total.subtract(ot);
                        })
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                overtimeHours = filtered.stream()
                        .map(a -> a.getOvertimeHours() != null ? a.getOvertimeHours() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
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

            // 5. Calculate Base Pay
            BigDecimal grossPay = BigDecimal.ZERO;
            BigDecimal unpaidLeaveDeductionAmount = BigDecimal.ZERO;
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
                BigDecimal overtimePay = emp.getHourlyRate().multiply(otMultiplier).multiply(overtimeHours);
                grossPay = normalPay.add(overtimePay);
            } else {
                // Monthly salaried - calculate base pay proportional to the date range (daysInPeriod / 30)
                BigDecimal dailyRate = emp.getBaseSalary().divide(new BigDecimal("30"), 4, RoundingMode.HALF_UP);
                BigDecimal basePayForPeriod = dailyRate.multiply(new BigDecimal(daysInPeriod)).setScale(2, RoundingMode.HALF_UP);
                unpaidLeaveDeductionAmount = dailyRate.multiply(BigDecimal.valueOf(unpaidLeaveDays)).setScale(2, RoundingMode.HALF_UP);
                grossPay = basePayForPeriod; // Exception-Based Pay: Gross pay is full, unpaid leave is a deduction
            }

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
        return SalarySlipDto.builder()
                .id(entity.getId())
                .employeeId(entity.getEmployee().getId())
                .employeeName(entity.getEmployee().getFirstName() + " " + entity.getEmployee().getLastName())
                .payrollRunId(entity.getPayrollRun().getId())
                .totalWorkedHours(entity.getTotalWorkedHours())
                .totalUnpaidLeaveDays(entity.getTotalUnpaidLeaveDays())
                .grossPay(entity.getGrossPay())
                .totalDeductions(entity.getTotalDeductions())
                .netPay(entity.getNetPay())
                .status(entity.getStatus())
                .build();
    }
}
