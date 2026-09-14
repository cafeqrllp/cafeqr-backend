package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.expense.domain.Expense;
import com.restaurant.pos.expense.repository.ExpenseRepository;
import com.restaurant.pos.category.domain.ExpenseCategory;
import com.restaurant.pos.category.repository.ExpenseCategoryRepository;
import com.restaurant.pos.hr.entity.PayrollRun;
import com.restaurant.pos.hr.entity.SalarySlip;
import com.restaurant.pos.hr.repository.PayrollRunRepository;
import com.restaurant.pos.hr.repository.SalarySlipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PayrollAccountingService {

    private final ExpenseRepository expenseRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final SalarySlipRepository salarySlipRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;

    @Transactional
    public void syncPayrollToAccounting(UUID payrollRunId, String paymentMethod) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        PayrollRun run = payrollRunRepository.findByIdAndClientIdAndOrgId(payrollRunId, clientId, orgId)
                .orElseThrow(() -> new RuntimeException("PayrollRun not found"));

        if ("PAID".equals(run.getStatus())) {
            throw new RuntimeException("This payroll run has already been synced to accounting.");
        }
        if (!"COMPLETED".equals(run.getStatus())) {
            throw new RuntimeException("Payroll Run must be completed before accounting sync");
        }

        List<SalarySlip> slips = salarySlipRepository.findByPayrollRunIdAndClientIdAndOrgId(payrollRunId, clientId, orgId);
        
        BigDecimal totalPayrollExpense = slips.stream()
                .map(SalarySlip::getGrossPay)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ExpenseCategory category = expenseCategoryRepository.findByNameIgnoreCaseAndClientIdAndOrgId("Payroll", clientId, orgId)
                .orElseGet(() -> {
                    ExpenseCategory newCategory = ExpenseCategory.builder()
                            .name("Payroll")
                            .sortOrder(99)
                            .build();
                    newCategory.setClientId(clientId);
                    newCategory.setOrgId(orgId);
                    return expenseCategoryRepository.save(newCategory);
                });

        // Create an Expense record in the main Cafe QR Accounting module
        Expense expense = Expense.builder()
                .expenseNo("PR-" + run.getId().toString().substring(0, 8).toUpperCase())
                .expenseDate(Instant.now())
                .categoryId(category.getId())
                .amount(totalPayrollExpense)
                .description("Payroll Disbursement for: " + run.getName())
                .paymentMethod(paymentMethod != null && !paymentMethod.trim().isEmpty() ? paymentMethod.toUpperCase() : "BANK_TRANSFER")
                .docStatus("COMPLETED")
                .paymentStatus("PAID")
                .build();
                
        // Inherit BaseEntity properties
        expense.setClientId(clientId);
        expense.setOrgId(orgId);

        expenseRepository.save(expense);
        
        // Mark run as PAID
        run.setStatus("PAID");
        payrollRunRepository.save(run);
        
        // Mark all slips as PAID
        for (SalarySlip slip : slips) {
            slip.setStatus("PAID");
            salarySlipRepository.save(slip);
        }
    }
}
