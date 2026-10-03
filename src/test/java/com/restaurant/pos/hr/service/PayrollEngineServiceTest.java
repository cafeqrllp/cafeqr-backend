package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.expense.repository.ExpenseRepository;
import com.restaurant.pos.hr.dto.HrSettingsDto;
import com.restaurant.pos.hr.dto.PayrollRunDto;
import com.restaurant.pos.hr.dto.SalarySlipDto;
import com.restaurant.pos.hr.entity.*;
import com.restaurant.pos.hr.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PayrollEngineServiceTest {

    private PayrollRunRepository payrollRunRepository;
    private SalarySlipRepository salarySlipRepository;
    private EmployeeRepository employeeRepository;
    private EmployeeSalaryComponentRepository employeeSalaryComponentRepository;
    private AttendanceRepository attendanceRepository;
    private LeaveRequestRepository leaveRequestRepository;
    private SalaryAdvanceRepository salaryAdvanceRepository;
    private HrSettingsService hrSettingsService;
    private ExpenseRepository expenseRepository;
    private com.restaurant.pos.common.service.AuditLogService auditLogService;

    private PayrollEngineService payrollEngineService;

    private UUID clientId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        payrollRunRepository = mock(PayrollRunRepository.class);
        salarySlipRepository = mock(SalarySlipRepository.class);
        employeeRepository = mock(EmployeeRepository.class);
        employeeSalaryComponentRepository = mock(EmployeeSalaryComponentRepository.class);
        attendanceRepository = mock(AttendanceRepository.class);
        leaveRequestRepository = mock(LeaveRequestRepository.class);
        salaryAdvanceRepository = mock(SalaryAdvanceRepository.class);
        hrSettingsService = mock(HrSettingsService.class);
        expenseRepository = mock(ExpenseRepository.class);
        auditLogService = mock(com.restaurant.pos.common.service.AuditLogService.class);

        payrollEngineService = new PayrollEngineService(
                payrollRunRepository,
                salarySlipRepository,
                employeeRepository,
                employeeSalaryComponentRepository,
                attendanceRepository,
                leaveRequestRepository,
                salaryAdvanceRepository,
                hrSettingsService,
                expenseRepository,
                auditLogService
        );


        clientId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);

        when(payrollRunRepository.save(any(PayrollRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(salarySlipRepository.save(any(SalarySlip.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void initiatePayrollRun_SalariedEmployee_CalculatesBaseAndUnpaidLeaves() {
        Employee emp = new Employee();
        emp.setFirstName("John");
        emp.setLastName("Doe");
        emp.setEmploymentType("SALARIED");
        emp.setBaseSalary(new BigDecimal("3000.00"));
        emp.setActive(true);

        PayrollRunDto runDto = PayrollRunDto.builder()
                .name("Sept 2026 Payroll")
                .startDate(LocalDate.of(2026, 9, 1))
                .endDate(LocalDate.of(2026, 9, 30))
                .build();

        LeaveRequest unpaidLeave = new LeaveRequest();
        unpaidLeave.setEmployee(emp);
        unpaidLeave.setLeaveType("UNPAID");
        unpaidLeave.setTotalDays(2);

        when(employeeRepository.findByClientIdAndOrgId(clientId, orgId)).thenReturn(List.of(emp));
        when(attendanceRepository.findByEmployeeIdAndDateRangeAndClientIdAndOrgId(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of());
        when(leaveRequestRepository.findApprovedByEmployeeIdAndDateRange(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of(unpaidLeave));
        when(employeeSalaryComponentRepository.findActiveByEmployeeId(emp.getId())).thenReturn(List.of());
        when(salaryAdvanceRepository.findActiveAdvancesByEmployeeId(emp.getId(), clientId, orgId)).thenReturn(List.of());

        PayrollRunDto result = payrollEngineService.initiatePayrollRun(runDto);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("COMPLETED");

        ArgumentCaptor<SalarySlip> slipCaptor = ArgumentCaptor.forClass(SalarySlip.class);
        verify(salarySlipRepository).save(slipCaptor.capture());
        SalarySlip savedSlip = slipCaptor.getValue();

        // Base 3000 / 30 = 100 daily rate. 2 unpaid days = 200 deduction.
        assertThat(savedSlip.getGrossPay()).isEqualByComparingTo("3000.00");
        assertThat(savedSlip.getTotalDeductions()).isEqualByComparingTo("200.00");
        assertThat(savedSlip.getNetPay()).isEqualByComparingTo("2800.00");
        assertThat(savedSlip.getTotalUnpaidLeaveDays()).isEqualTo(2);
    }

    @Test
    void initiatePayrollRun_HourlyEmployee_CalculatesRegularAndOvertimePayAt1Point5x() {
        Employee emp = new Employee();
        emp.setFirstName("Alice");
        emp.setLastName("Smith");
        emp.setEmploymentType("HOURLY");
        emp.setHourlyRate(new BigDecimal("20.00"));
        emp.setActive(true);

        Attendance att1 = new Attendance();
        att1.setTotalHoursWorked(new BigDecimal("100.00"));
        att1.setOvertimeHours(new BigDecimal("10.00")); // 90 normal, 10 OT

        Attendance att2 = new Attendance();
        att2.setTotalHoursWorked(new BigDecimal("60.00"));
        att2.setOvertimeHours(new BigDecimal("10.00")); // 50 normal, 10 OT

        PayrollRunDto runDto = PayrollRunDto.builder()
                .name("Hourly Sept 2026")
                .startDate(LocalDate.of(2026, 9, 1))
                .endDate(LocalDate.of(2026, 9, 30))
                .build();

        when(employeeRepository.findByClientIdAndOrgId(clientId, orgId)).thenReturn(List.of(emp));
        when(attendanceRepository.findByEmployeeIdAndDateRangeAndClientIdAndOrgId(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of(att1, att2));
        when(leaveRequestRepository.findApprovedByEmployeeIdAndDateRange(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of());
        when(employeeSalaryComponentRepository.findActiveByEmployeeId(emp.getId())).thenReturn(List.of());
        when(salaryAdvanceRepository.findActiveAdvancesByEmployeeId(emp.getId(), clientId, orgId)).thenReturn(List.of());

        payrollEngineService.initiatePayrollRun(runDto);

        ArgumentCaptor<SalarySlip> slipCaptor = ArgumentCaptor.forClass(SalarySlip.class);
        verify(salarySlipRepository).save(slipCaptor.capture());
        SalarySlip savedSlip = slipCaptor.getValue();

        // Total hours = 160 (140 normal + 20 OT)
        // Normal pay = 140 * $20 = $2800
        // Overtime pay = 20 * ($20 * 1.5) = 20 * $30 = $600
        // Gross pay = $3400
        assertThat(savedSlip.getTotalWorkedHours()).isEqualByComparingTo("160.00");
        assertThat(savedSlip.getGrossPay()).isEqualByComparingTo("3400.00");
        assertThat(savedSlip.getNetPay()).isEqualByComparingTo("3400.00");
    }

    @Test
    void initiatePayrollRun_WithFixedAndPercentageSalaryComponents() {
        Employee emp = new Employee();
        emp.setFirstName("Bob");
        emp.setLastName("Builder");
        emp.setEmploymentType("SALARIED");
        emp.setBaseSalary(new BigDecimal("4000.00"));
        emp.setActive(true);

        PayrollRunDto runDto = PayrollRunDto.builder()
                .name("Components Run")
                .startDate(LocalDate.of(2026, 9, 1))
                .endDate(LocalDate.of(2026, 9, 30))
                .build();

        // 1. Fixed Allowance (EARNING) of $300
        SalaryComponent allowanceComp = new SalaryComponent();
        allowanceComp.setName("Transport Allowance");
        allowanceComp.setType("EARNING");
        allowanceComp.setAmountType("FIXED");
        allowanceComp.setDefaultAmount(new BigDecimal("300.00"));

        EmployeeSalaryComponent empAllowance = new EmployeeSalaryComponent();
        empAllowance.setEmployee(emp);
        empAllowance.setSalaryComponent(allowanceComp);
        empAllowance.setActive(true);

        // 2. Percentage Tax (DEDUCTION) of 10%
        SalaryComponent taxComp = new SalaryComponent();
        taxComp.setName("Income Tax");
        taxComp.setType("DEDUCTION");
        taxComp.setAmountType("PERCENTAGE");
        taxComp.setPercentage(new BigDecimal("10.00"));

        EmployeeSalaryComponent empTax = new EmployeeSalaryComponent();
        empTax.setEmployee(emp);
        empTax.setSalaryComponent(taxComp);
        empTax.setActive(true);

        when(employeeRepository.findByClientIdAndOrgId(clientId, orgId)).thenReturn(List.of(emp));
        when(attendanceRepository.findByEmployeeIdAndDateRangeAndClientIdAndOrgId(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of());
        when(leaveRequestRepository.findApprovedByEmployeeIdAndDateRange(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of());
        when(employeeSalaryComponentRepository.findActiveByEmployeeId(emp.getId())).thenReturn(List.of(empAllowance, empTax));
        when(salaryAdvanceRepository.findActiveAdvancesByEmployeeId(emp.getId(), clientId, orgId)).thenReturn(List.of());

        payrollEngineService.initiatePayrollRun(runDto);

        ArgumentCaptor<SalarySlip> slipCaptor = ArgumentCaptor.forClass(SalarySlip.class);
        verify(salarySlipRepository).save(slipCaptor.capture());
        SalarySlip savedSlip = slipCaptor.getValue();

        // Gross = 4000 + 300 = 4300.00
        // Deduction = 4300 * 10% = 430.00
        // Net = 4300 - 430 = 3870.00
        assertThat(savedSlip.getGrossPay()).isEqualByComparingTo("4300.00");
        assertThat(savedSlip.getTotalDeductions()).isEqualByComparingTo("430.00");
        assertThat(savedSlip.getNetPay()).isEqualByComparingTo("3870.00");
    }

    @Test
    void initiatePayrollRun_DeductsSalaryAdvanceAndUpdatesRemainingBalance() {
        Employee emp = new Employee();
        emp.setFirstName("Charlie");
        emp.setLastName("Brown");
        emp.setEmploymentType("SALARIED");
        emp.setBaseSalary(new BigDecimal("3000.00"));
        emp.setActive(true);

        PayrollRunDto runDto = PayrollRunDto.builder()
                .name("Advance Run")
                .startDate(LocalDate.of(2026, 9, 1))
                .endDate(LocalDate.of(2026, 9, 30))
                .build();

        SalaryAdvance advance = new SalaryAdvance();
        advance.setEmployee(emp);
        advance.setTotalAmount(new BigDecimal("500.00"));
        advance.setMonthlyInstallmentAmount(new BigDecimal("200.00"));
        advance.setRemainingBalance(new BigDecimal("500.00"));
        advance.setStatus("APPROVED");

        when(employeeRepository.findByClientIdAndOrgId(clientId, orgId)).thenReturn(List.of(emp));
        when(attendanceRepository.findByEmployeeIdAndDateRangeAndClientIdAndOrgId(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of());
        when(leaveRequestRepository.findApprovedByEmployeeIdAndDateRange(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of());
        when(employeeSalaryComponentRepository.findActiveByEmployeeId(emp.getId())).thenReturn(List.of());
        when(salaryAdvanceRepository.findActiveAdvancesByEmployeeId(emp.getId(), clientId, orgId)).thenReturn(List.of(advance));

        payrollEngineService.initiatePayrollRun(runDto);

        ArgumentCaptor<SalarySlip> slipCaptor = ArgumentCaptor.forClass(SalarySlip.class);
        verify(salarySlipRepository).save(slipCaptor.capture());
        SalarySlip savedSlip = slipCaptor.getValue();

        assertThat(savedSlip.getGrossPay()).isEqualByComparingTo("3000.00");
        assertThat(savedSlip.getTotalDeductions()).isEqualByComparingTo("200.00");
        assertThat(savedSlip.getNetPay()).isEqualByComparingTo("2800.00");

        ArgumentCaptor<SalaryAdvance> advanceCaptor = ArgumentCaptor.forClass(SalaryAdvance.class);
        verify(salaryAdvanceRepository).save(advanceCaptor.capture());
        SalaryAdvance savedAdvance = advanceCaptor.getValue();

        assertThat(savedAdvance.getRemainingBalance()).isEqualByComparingTo("300.00");
        assertThat(savedAdvance.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void initiatePayrollRun_SalaryAdvanceFinalPayment_MarksStatusPaid() {
        Employee emp = new Employee();
        emp.setFirstName("Dave");
        emp.setLastName("Miller");
        emp.setEmploymentType("SALARIED");
        emp.setBaseSalary(new BigDecimal("3000.00"));
        emp.setActive(true);

        PayrollRunDto runDto = PayrollRunDto.builder()
                .name("Final Advance Run")
                .startDate(LocalDate.of(2026, 9, 1))
                .endDate(LocalDate.of(2026, 9, 30))
                .build();

        SalaryAdvance advance = new SalaryAdvance();
        advance.setEmployee(emp);
        advance.setTotalAmount(new BigDecimal("500.00"));
        advance.setMonthlyInstallmentAmount(new BigDecimal("200.00"));
        advance.setRemainingBalance(new BigDecimal("150.00")); // Less than monthly installment
        advance.setStatus("APPROVED");

        when(employeeRepository.findByClientIdAndOrgId(clientId, orgId)).thenReturn(List.of(emp));
        when(attendanceRepository.findByEmployeeIdAndDateRangeAndClientIdAndOrgId(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of());
        when(leaveRequestRepository.findApprovedByEmployeeIdAndDateRange(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of());
        when(employeeSalaryComponentRepository.findActiveByEmployeeId(emp.getId())).thenReturn(List.of());
        when(salaryAdvanceRepository.findActiveAdvancesByEmployeeId(emp.getId(), clientId, orgId)).thenReturn(List.of(advance));

        payrollEngineService.initiatePayrollRun(runDto);

        ArgumentCaptor<SalaryAdvance> advanceCaptor = ArgumentCaptor.forClass(SalaryAdvance.class);
        verify(salaryAdvanceRepository).save(advanceCaptor.capture());
        SalaryAdvance savedAdvance = advanceCaptor.getValue();

        assertThat(savedAdvance.getRemainingBalance()).isEqualByComparingTo("0.00");
        assertThat(savedAdvance.getStatus()).isEqualTo("PAID");
    }

    @Test
    void initiatePayrollRun_NetPayFlooredAtZeroWhenDeductionsExceedGross() {
        Employee emp = new Employee();
        emp.setFirstName("Eva");
        emp.setLastName("Green");
        emp.setEmploymentType("SALARIED");
        emp.setBaseSalary(new BigDecimal("500.00"));
        emp.setActive(true);

        PayrollRunDto runDto = PayrollRunDto.builder()
                .name("High Deduction Run")
                .startDate(LocalDate.of(2026, 9, 1))
                .endDate(LocalDate.of(2026, 9, 30))
                .build();

        SalaryComponent largeDeduction = new SalaryComponent();
        largeDeduction.setName("Court Fine");
        largeDeduction.setType("DEDUCTION");
        largeDeduction.setAmountType("FIXED");
        largeDeduction.setDefaultAmount(new BigDecimal("800.00"));

        EmployeeSalaryComponent empDeduction = new EmployeeSalaryComponent();
        empDeduction.setEmployee(emp);
        empDeduction.setSalaryComponent(largeDeduction);
        empDeduction.setActive(true);

        when(employeeRepository.findByClientIdAndOrgId(clientId, orgId)).thenReturn(List.of(emp));
        when(attendanceRepository.findByEmployeeIdAndDateRangeAndClientIdAndOrgId(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of());
        when(leaveRequestRepository.findApprovedByEmployeeIdAndDateRange(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of());
        when(employeeSalaryComponentRepository.findActiveByEmployeeId(emp.getId())).thenReturn(List.of(empDeduction));
        when(salaryAdvanceRepository.findActiveAdvancesByEmployeeId(emp.getId(), clientId, orgId)).thenReturn(List.of());

        payrollEngineService.initiatePayrollRun(runDto);

        ArgumentCaptor<SalarySlip> slipCaptor = ArgumentCaptor.forClass(SalarySlip.class);
        verify(salarySlipRepository).save(slipCaptor.capture());
        SalarySlip savedSlip = slipCaptor.getValue();

        assertThat(savedSlip.getGrossPay()).isEqualByComparingTo("500.00");
        assertThat(savedSlip.getTotalDeductions()).isEqualByComparingTo("800.00");
        assertThat(savedSlip.getNetPay()).isEqualByComparingTo("0.00"); // Capped at zero
    }

    @Test
    void getAllPayrollRuns_ScopesToTenantContext() {
        PayrollRun run1 = new PayrollRun();
        run1.setName("Run 1");
        run1.setStartDate(LocalDate.of(2026, 8, 1));
        run1.setEndDate(LocalDate.of(2026, 8, 31));
        run1.setStatus("COMPLETED");

        when(payrollRunRepository.findByClientIdAndOrgId(clientId, orgId)).thenReturn(List.of(run1));

        List<PayrollRunDto> runs = payrollEngineService.getAllPayrollRuns();

        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).getName()).isEqualTo("Run 1");
        verify(payrollRunRepository).findByClientIdAndOrgId(clientId, orgId);
    }

    @Test
    void getSlipsForRun_ScopesToTenantContext() {
        UUID runId = UUID.randomUUID();
        Employee emp = new Employee();
        emp.setFirstName("Frank");
        emp.setLastName("Castle");

        PayrollRun run = new PayrollRun();
        run.setName("Run 1");

        SalarySlip slip = new SalarySlip();
        slip.setEmployee(emp);
        slip.setPayrollRun(run);
        slip.setTotalWorkedHours(new BigDecimal("160.00"));
        slip.setGrossPay(new BigDecimal("2500.00"));
        slip.setNetPay(new BigDecimal("2200.00"));
        slip.setStatus("GENERATED");

        when(salarySlipRepository.findByPayrollRunIdAndClientIdAndOrgId(runId, clientId, orgId))
                .thenReturn(List.of(slip));

        List<SalarySlipDto> slips = payrollEngineService.getSlipsForRun(runId);

        assertThat(slips).hasSize(1);
        assertThat(slips.get(0).getEmployeeName()).isEqualTo("Frank Castle");
        assertThat(slips.get(0).getNetPay()).isEqualByComparingTo("2200.00");
        verify(salarySlipRepository).findByPayrollRunIdAndClientIdAndOrgId(runId, clientId, orgId);
    }

    @Test
    void initiatePayrollRun_MultiMonthPeriod_ScalesBaseSalaryProportionally() {
        Employee emp = new Employee();
        emp.setFirstName("Vikky");
        emp.setLastName("J");
        emp.setEmploymentType("SALARIED");
        emp.setBaseSalary(new BigDecimal("1200.00")); // Monthly salary = $1200
        emp.setActive(true);

        // Date range for 2 months (Sept 1 to Oct 31 = 61 days)
        PayrollRunDto runDto = PayrollRunDto.builder()
                .name("Sep oct")
                .startDate(LocalDate.of(2026, 9, 1))
                .endDate(LocalDate.of(2026, 10, 31))
                .build();

        when(employeeRepository.findByClientIdAndOrgId(clientId, orgId)).thenReturn(List.of(emp));
        when(attendanceRepository.findByEmployeeIdAndDateRangeAndClientIdAndOrgId(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of());
        when(leaveRequestRepository.findApprovedByEmployeeIdAndDateRange(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of());
        when(employeeSalaryComponentRepository.findActiveByEmployeeId(emp.getId())).thenReturn(List.of());
        when(salaryAdvanceRepository.findActiveAdvancesByEmployeeId(emp.getId(), clientId, orgId)).thenReturn(List.of());

        payrollEngineService.initiatePayrollRun(runDto);

        ArgumentCaptor<SalarySlip> slipCaptor = ArgumentCaptor.forClass(SalarySlip.class);
        verify(salarySlipRepository).save(slipCaptor.capture());
        SalarySlip savedSlip = slipCaptor.getValue();

        // 61 days at $40/day = $2440.00 gross pay (full 2-month period)
        assertThat(savedSlip.getGrossPay()).isEqualByComparingTo("2440.00");
    }

    /**
     * CQR-135: Monthly-salaried employees must receive overtime pay when their
     * timecards accumulate overtime hours beyond the standard daily threshold.
     *
     * Setup:
     *   - Employee base salary: $3,000/month
     *   - Period: 30-day month (Sep 2026)
     *   - Timecard: 5 hours of overtime accumulated across the period
     *   - HR settings: standardHoursPerDay = 8, overtimeMultiplier = 1.5
     *
     * Expected calculation:
     *   - Daily rate       = $3000 / 30 = $100.00
     *   - Base pay         = $100 * 30   = $3000.00
     *   - Effective hourly = $100 / 8    = $12.50/hr
     *   - OT pay           = $12.50 * 1.5 * 5 = $93.75
     *   - Gross pay        = $3000.00 + $93.75 = $3093.75
     */
    @Test
    void initiatePayrollRun_MonthlySalariedEmployee_IncludesOvertimePay_CQR135() {
        Employee emp = new Employee();
        emp.setFirstName("Sara");
        emp.setLastName("Monthly");
        emp.setEmploymentType("SALARIED");
        emp.setBaseSalary(new BigDecimal("3000.00"));
        emp.setActive(true);

        // Timecard: 13 total hours on one day — 8 normal, 5 OT
        Attendance att = new Attendance();
        att.setTotalHoursWorked(new BigDecimal("13.00"));
        att.setOvertimeHours(new BigDecimal("5.00"));

        PayrollRunDto runDto = PayrollRunDto.builder()
                .name("Monthly OT Test Run")
                .startDate(LocalDate.of(2026, 9, 1))
                .endDate(LocalDate.of(2026, 9, 30))
                .build();

        HrSettingsDto settings = HrSettingsDto.builder()
                .standardHoursPerDay(new BigDecimal("8.00"))
                .overtimeMultiplier(new BigDecimal("1.50"))
                .build();

        when(hrSettingsService.getSettings()).thenReturn(settings);
        when(employeeRepository.findByClientIdAndOrgId(clientId, orgId)).thenReturn(List.of(emp));
        when(attendanceRepository.findByEmployeeIdAndDateRangeAndClientIdAndOrgId(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of(att));
        when(leaveRequestRepository.findApprovedByEmployeeIdAndDateRange(eq(emp.getId()), any(), any(), eq(clientId), eq(orgId)))
                .thenReturn(List.of());
        when(employeeSalaryComponentRepository.findActiveByEmployeeId(emp.getId())).thenReturn(List.of());
        when(salaryAdvanceRepository.findActiveAdvancesByEmployeeId(emp.getId(), clientId, orgId)).thenReturn(List.of());

        payrollEngineService.initiatePayrollRun(runDto);

        ArgumentCaptor<SalarySlip> slipCaptor = ArgumentCaptor.forClass(SalarySlip.class);
        verify(salarySlipRepository).save(slipCaptor.capture());
        SalarySlip savedSlip = slipCaptor.getValue();

        // Base pay: $3000/30 * 30 = $3000.00
        // Effective hourly: $100 / 8hrs = $12.50
        // OT pay: $12.50 * 1.5 * 5hrs = $93.75
        // Gross pay: $3000.00 + $93.75 = $3093.75
        assertThat(savedSlip.getTotalWorkedHours()).isEqualByComparingTo("13.00");
        assertThat(savedSlip.getGrossPay()).isEqualByComparingTo("3093.75");
        assertThat(savedSlip.getNetPay()).isEqualByComparingTo("3093.75");
    }

    @Test
    void initiatePayrollRun_DuplicateRunName_ThrowsBusinessException_CQR123() {
        PayrollRunDto runDto = PayrollRunDto.builder()
                .name("September 2026 Payroll")
                .startDate(LocalDate.of(2026, 9, 1))
                .endDate(LocalDate.of(2026, 9, 30))
                .build();

        when(payrollRunRepository.existsByNameAndClientIdAndOrgId(eq("September 2026 Payroll"), eq(clientId), eq(orgId)))
                .thenReturn(true);

        assertThatThrownBy(() -> payrollEngineService.initiatePayrollRun(runDto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("A payroll run with the name 'September 2026 Payroll' already exists.");
    }

    @Test
    void initiatePayrollRun_EmptyName_ThrowsBusinessException_CQR123() {
        PayrollRunDto runDto = PayrollRunDto.builder()
                .name("   ")
                .startDate(LocalDate.of(2026, 9, 1))
                .endDate(LocalDate.of(2026, 9, 30))
                .build();

        assertThatThrownBy(() -> payrollEngineService.initiatePayrollRun(runDto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Payroll run name cannot be empty.");
    }

    @Test
    void initiatePayrollRun_StartDateAfterEndDate_ThrowsBusinessException_CQR125() {
        PayrollRunDto runDto = PayrollRunDto.builder()
                .name("Invalid Date Run")
                .startDate(LocalDate.of(2026, 10, 15))
                .endDate(LocalDate.of(2026, 10, 1))
                .build();

        assertThatThrownBy(() -> payrollEngineService.initiatePayrollRun(runDto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Start date cannot be after end date.");
    }

    @Test
    void initiatePayrollRun_InvalidYearRange_ThrowsBusinessException_CQR125() {
        PayrollRunDto runDto = PayrollRunDto.builder()
                .name("Ancient Date Run")
                .startDate(LocalDate.of(1111, 4, 11))
                .endDate(LocalDate.of(2026, 10, 1))
                .build();

        assertThatThrownBy(() -> payrollEngineService.initiatePayrollRun(runDto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid date range: Year must be between 2000 and 2100.");
    }

    @Test
    void deletePayrollRun_RecordsAuditLog_CQR135_SubItem4() {
        UUID runId = UUID.randomUUID();
        PayrollRun run = new PayrollRun();
        run.setId(runId);

        when(payrollRunRepository.findByIdAndClientIdAndOrgId(eq(runId), eq(clientId), eq(orgId)))
                .thenReturn(Optional.of(run));
        when(salarySlipRepository.findByPayrollRunIdAndClientIdAndOrgId(eq(runId), eq(clientId), eq(orgId)))
                .thenReturn(List.of());
        when(expenseRepository.findByClientIdAndOrgIdAndExpenseNo(any(), any(), any()))
                .thenReturn(List.of());

        payrollEngineService.deletePayrollRun(runId);

        verify(payrollRunRepository).delete(run);
        verify(auditLogService).logAction("DELETE_PAYROLL_RUN", "PayrollRun", runId.toString());
    }

    @Test
    void calculateOvertimeHours_BothMode_TakesHigherOfDailyOrWeekly_CQR135_SubItem6() {
        // Mon 10h (2h daily OT), Tue 10h (2h daily OT), Wed 10h (2h daily OT) -> 30h total worked
        // Daily OT sum = 6h. Weekly OT (threshold 40h) = 0h.
        // BOTH mode should return Max(6, 0) = 6h.
        Attendance a1 = new Attendance();
        a1.setAttendanceDate(LocalDate.of(2026, 9, 7)); // Monday
        a1.setTotalHoursWorked(new BigDecimal("10.00"));
        a1.setOvertimeHours(new BigDecimal("2.00"));

        Attendance a2 = new Attendance();
        a2.setAttendanceDate(LocalDate.of(2026, 9, 8)); // Tuesday
        a2.setTotalHoursWorked(new BigDecimal("10.00"));
        a2.setOvertimeHours(new BigDecimal("2.00"));

        Attendance a3 = new Attendance();
        a3.setAttendanceDate(LocalDate.of(2026, 9, 9)); // Wednesday
        a3.setTotalHoursWorked(new BigDecimal("10.00"));
        a3.setOvertimeHours(new BigDecimal("2.00"));

        com.restaurant.pos.hr.dto.HrSettingsDto settings = com.restaurant.pos.hr.dto.HrSettingsDto.builder()
                .overtimeMode("BOTH")
                .weeklyOvertimeThreshold(new BigDecimal("40.00"))
                .build();

        BigDecimal otBoth = payrollEngineService.calculateOvertimeHours(List.of(a1, a2, a3), settings);
        assertThat(otBoth).isEqualByComparingTo("6.00");

        // Now test 7 days of 8h (0h daily OT) = 56h total worked.
        // Daily OT sum = 0h. Weekly OT (56 - 40) = 16h.
        // BOTH mode should return Max(0, 16) = 16h.
        List<Attendance> sevenDays = new java.util.ArrayList<>();
        for (int day = 7; day <= 13; day++) {
            Attendance a = new Attendance();
            a.setAttendanceDate(LocalDate.of(2026, 9, day));
            a.setTotalHoursWorked(new BigDecimal("8.00"));
            a.setOvertimeHours(BigDecimal.ZERO);
            sevenDays.add(a);
        }

        BigDecimal otWeeklyHigher = payrollEngineService.calculateOvertimeHours(sevenDays, settings);
        assertThat(otWeeklyHigher).isEqualByComparingTo("16.00");
    }
}




