package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.entity.Employee;
import com.restaurant.pos.hr.entity.SalarySlip;
import com.restaurant.pos.hr.repository.SalarySlipRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PayrollExportServiceTest {

    private SalarySlipRepository salarySlipRepository;
    private PayrollExportService service;

    private UUID clientId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        salarySlipRepository = mock(SalarySlipRepository.class);
        service = new PayrollExportService(salarySlipRepository);

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
    void generateAchExport_EmptySlips_ReturnsEmptyString() {
        UUID runId = UUID.randomUUID();
        when(salarySlipRepository.findByPayrollRunIdAndClientIdAndOrgId(runId, clientId, orgId))
                .thenReturn(Collections.emptyList());

        String result = service.generateAchExport(runId);

        assertThat(result).isEmpty();
    }

    @Test
    void generateAchExport_ValidEmployeeWithBank_GeneratesRecord() {
        UUID runId = UUID.randomUUID();

        Employee emp = new Employee();
        emp.setFirstName("Jane");
        emp.setLastName("Smith");
        emp.setBankAccountNumber("12345678");
        emp.setBankRoutingNumber("123456789");

        SalarySlip slip = new SalarySlip();
        slip.setEmployee(emp);
        slip.setNetPay(new BigDecimal("1500.00"));

        when(salarySlipRepository.findByPayrollRunIdAndClientIdAndOrgId(runId, clientId, orgId))
                .thenReturn(List.of(slip));

        String result = service.generateAchExport(runId);

        assertThat(result).isNotNull();
        assertThat(result).contains("123456789");
        assertThat(result).contains("Jane Smith");
    }
}
