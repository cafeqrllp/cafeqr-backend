package com.restaurant.pos.hr.service;

import com.restaurant.pos.category.domain.ExpenseCategory;
import com.restaurant.pos.category.repository.ExpenseCategoryRepository;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.expense.domain.Expense;
import com.restaurant.pos.expense.repository.ExpenseRepository;
import com.restaurant.pos.hr.entity.PayrollRun;
import com.restaurant.pos.hr.entity.SalarySlip;
import com.restaurant.pos.hr.repository.PayrollRunRepository;
import com.restaurant.pos.hr.repository.SalarySlipRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PayrollAccountingServiceTest {

    private ExpenseRepository expenseRepository;
    private PayrollRunRepository payrollRunRepository;
    private SalarySlipRepository salarySlipRepository;
    private ExpenseCategoryRepository expenseCategoryRepository;

    private PayrollAccountingService payrollAccountingService;

    private UUID clientId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ExpenseRepository.class);
        payrollRunRepository = mock(PayrollRunRepository.class);
        salarySlipRepository = mock(SalarySlipRepository.class);
        expenseCategoryRepository = mock(ExpenseCategoryRepository.class);

        payrollAccountingService = new PayrollAccountingService(
                expenseRepository,
                payrollRunRepository,
                salarySlipRepository,
                expenseCategoryRepository
        );

        clientId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void syncPayrollToAccounting_AlreadySynced_ThrowsBusinessException_CQR128() {
        UUID runId = UUID.randomUUID();
        PayrollRun run = new PayrollRun();
        run.setStatus("PAID");

        when(payrollRunRepository.findByIdAndClientIdAndOrgId(eq(runId), eq(clientId), eq(orgId)))
                .thenReturn(Optional.of(run));

        assertThatThrownBy(() -> payrollAccountingService.syncPayrollToAccounting(runId, "BANK_TRANSFER"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("This payroll run has already been synced with Accounting.");
    }

    @Test
    void syncPayrollToAccounting_NotCompleted_ThrowsBusinessException() {
        UUID runId = UUID.randomUUID();
        PayrollRun run = new PayrollRun();
        run.setStatus("PROCESSING");

        when(payrollRunRepository.findByIdAndClientIdAndOrgId(eq(runId), eq(clientId), eq(orgId)))
                .thenReturn(Optional.of(run));

        assertThatThrownBy(() -> payrollAccountingService.syncPayrollToAccounting(runId, "BANK_TRANSFER"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Payroll Run must be completed before accounting sync.");
    }

    @Test
    void syncPayrollToAccounting_Success_CreatesExpenseAndMarksPaid() {
        UUID runId = UUID.randomUUID();
        PayrollRun run = new PayrollRun();
        run.setName("September 2026 Payroll");
        run.setStatus("COMPLETED");

        SalarySlip slip1 = new SalarySlip();
        slip1.setGrossPay(new BigDecimal("2500.00"));

        SalarySlip slip2 = new SalarySlip();
        slip2.setGrossPay(new BigDecimal("1500.00"));

        ExpenseCategory category = ExpenseCategory.builder()
                .name("Payroll")
                .build();
        category.setId(UUID.randomUUID());

        when(payrollRunRepository.findByIdAndClientIdAndOrgId(eq(runId), eq(clientId), eq(orgId)))
                .thenReturn(Optional.of(run));
        when(salarySlipRepository.findByPayrollRunIdAndClientIdAndOrgId(eq(runId), eq(clientId), eq(orgId)))
                .thenReturn(List.of(slip1, slip2));
        when(expenseCategoryRepository.findByNameIgnoreCaseAndClientIdAndOrgId(eq("Payroll"), eq(clientId), eq(orgId)))
                .thenReturn(Optional.of(category));

        payrollAccountingService.syncPayrollToAccounting(runId, "BANK_TRANSFER");

        ArgumentCaptor<Expense> expenseCaptor = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).save(expenseCaptor.capture());
        Expense savedExpense = expenseCaptor.getValue();

        assertThat(savedExpense.getAmount()).isEqualByComparingTo("4000.00");
        assertThat(savedExpense.getPaymentMethod()).isEqualTo("BANK_TRANSFER");
        assertThat(savedExpense.getPaymentStatus()).isEqualTo("PAID");

        assertThat(run.getStatus()).isEqualTo("PAID");
        assertThat(slip1.getStatus()).isEqualTo("PAID");
        assertThat(slip2.getStatus()).isEqualTo("PAID");
    }
}
