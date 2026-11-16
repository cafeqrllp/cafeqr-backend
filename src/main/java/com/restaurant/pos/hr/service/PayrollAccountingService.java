package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.category.domain.ExpenseCategory;
import com.restaurant.pos.category.repository.ExpenseCategoryRepository;
import com.restaurant.pos.expense.dto.CreateExpenseRequest;
import com.restaurant.pos.expense.service.ExpenseService;
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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PayrollAccountingService {

    private static final Set<String> EXPENSE_PAYMENT_METHODS = Set.of("CASH", "ONLINE", "UPI", "CARD", "BANK", "CHEQUE");

    private final ExpenseService expenseService;
    private final PayrollRunRepository payrollRunRepository;
    private final SalarySlipRepository salarySlipRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;

    @Transactional
    public void syncPayrollToAccounting(UUID payrollRunId, String paymentMethod) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        PayrollRun run = payrollRunRepository.findByIdAndClientIdAndOrgId(payrollRunId, clientId, orgId)
                .orElseThrow(() -> new BusinessException("Payroll run not found."));

        if ("PAID".equals(run.getStatus())) {
            throw new BusinessException("This payroll run has already been synced with Accounting.");
        }
        if (!"COMPLETED".equals(run.getStatus())) {
            throw new BusinessException("Payroll Run must be completed before accounting sync.");
        }

        List<SalarySlip> slips = salarySlipRepository.findByPayrollRunIdAndClientIdAndOrgId(payrollRunId, clientId, orgId);

        // What actually leaves the business is the net pay (gross pay less deductions), so that is
        // the amount recorded as paid. Recording gross pay would overstate the cash/bank outflow.
        BigDecimal totalNetPay = slips.stream()
                .map(slip -> slip.getNetPay() != null ? slip.getNetPay() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalNetPay.signum() > 0) {
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

            // Go through the expense service so the payroll gets its expense receipt, payment and
            // journal entries (Dr payroll expense / Cr cash or bank) like every other expense.
            CreateExpenseRequest request = CreateExpenseRequest.builder()
                    .categoryId(category.getId())
                    .expenseDate(Instant.now())
                    .amount(totalNetPay)
                    .description("Payroll Disbursement for: " + run.getName())
                    .paymentMethod(normalizePaymentMethod(paymentMethod))
                    .branchId(orgId)
                    .scope("BRANCH")
                    .build();
            expenseService.createExpense("payroll-run-" + payrollRunId, request);
        }

        // Mark run as PAID
        run.setStatus("PAID");
        payrollRunRepository.save(run);

        // Mark all slips as PAID
        for (SalarySlip slip : slips) {
            slip.setStatus("PAID");
            salarySlipRepository.save(slip);
        }
    }

    /** Maps the payroll screen's payment method onto the methods the expense module accepts. */
    private String normalizePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "BANK";
        }
        String method = paymentMethod.trim().toUpperCase(Locale.ROOT);
        if ("BANK_TRANSFER".equals(method)) {
            return "BANK";
        }
        return EXPENSE_PAYMENT_METHODS.contains(method) ? method : "BANK";
    }
}
