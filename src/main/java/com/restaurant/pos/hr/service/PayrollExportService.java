package com.restaurant.pos.hr.service;

import com.ancientprogramming.fixedformat4j.format.FixedFormatManager;
import com.ancientprogramming.fixedformat4j.format.impl.FixedFormatManagerImpl;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.dto.AchExportRecord;
import com.restaurant.pos.hr.entity.SalarySlip;
import com.restaurant.pos.hr.repository.SalarySlipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PayrollExportService {

    private final SalarySlipRepository salarySlipRepository;

    @Transactional(readOnly = true)
    public String generateAchExport(UUID payrollRunId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        List<SalarySlip> slips = salarySlipRepository.findByPayrollRunIdAndClientIdAndOrgId(payrollRunId, clientId, orgId);
        
        FixedFormatManager manager = new FixedFormatManagerImpl();
        StringBuilder exportFile = new StringBuilder();
        
        for (SalarySlip slip : slips) {
            // Only export for employees with bank details
            if (slip.getEmployee().getBankAccountNumber() != null && slip.getEmployee().getBankRoutingNumber() != null) {
                AchExportRecord record = new AchExportRecord();
                record.setRoutingNumber(slip.getEmployee().getBankRoutingNumber());
                record.setAccountNumber(slip.getEmployee().getBankAccountNumber());
                // Multiply net pay by 100 for ACH standard (implied decimal)
                record.setAmount(slip.getNetPay().multiply(new java.math.BigDecimal("100")));
                
                String name = slip.getEmployee().getFirstName() + " " + slip.getEmployee().getLastName();
                if (name.length() > 22) name = name.substring(0, 22);
                record.setEmployeeName(name);
                
                exportFile.append(manager.export(record)).append("\n");
            }
        }
        
        return exportFile.toString();
    }
}
