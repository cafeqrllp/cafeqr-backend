package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.dto.EmployeeSalaryComponentDto;
import com.restaurant.pos.hr.entity.Employee;
import com.restaurant.pos.hr.entity.EmployeeSalaryComponent;
import com.restaurant.pos.hr.entity.SalaryComponent;
import com.restaurant.pos.hr.repository.EmployeeRepository;
import com.restaurant.pos.hr.repository.EmployeeSalaryComponentRepository;
import com.restaurant.pos.hr.repository.SalaryComponentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmployeeSalaryComponentServiceTest {

    private EmployeeSalaryComponentRepository employeeSalaryComponentRepository;
    private EmployeeRepository employeeRepository;
    private SalaryComponentRepository salaryComponentRepository;

    private EmployeeSalaryComponentService service;

    private UUID clientId;
    private UUID orgId;
    private UUID employeeId;
    private UUID componentId;

    private Employee employee;
    private SalaryComponent salaryComponent;

    @BeforeEach
    void setUp() {
        employeeSalaryComponentRepository = mock(EmployeeSalaryComponentRepository.class);
        employeeRepository = mock(EmployeeRepository.class);
        salaryComponentRepository = mock(SalaryComponentRepository.class);

        service = new EmployeeSalaryComponentService(
                employeeSalaryComponentRepository,
                employeeRepository,
                salaryComponentRepository
        );

        clientId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        componentId = UUID.randomUUID();

        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(orgId);

        employee = new Employee();
        employee.setId(employeeId);
        employee.setClientId(clientId);
        employee.setOrgId(orgId);

        salaryComponent = new SalaryComponent();
        salaryComponent.setId(componentId);
        salaryComponent.setName("Bike Allowance");
        salaryComponent.setType("EARNING");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void assignComponentToEmployee_NewComponent_Success() {
        when(employeeRepository.findByIdAndClientIdAndOrgId(employeeId, clientId, orgId))
                .thenReturn(Optional.of(employee));
        when(salaryComponentRepository.findByIdAndClientIdAndOrgIdOrGlobal(componentId, clientId, orgId))
                .thenReturn(Optional.of(salaryComponent));
        when(employeeSalaryComponentRepository.findAllByEmployeeIdAndSalaryComponentId(employeeId, componentId))
                .thenReturn(List.of());
        when(employeeSalaryComponentRepository.save(any(EmployeeSalaryComponent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeSalaryComponentDto dto = EmployeeSalaryComponentDto.builder()
                .salaryComponentId(componentId)
                .overrideAmount(new BigDecimal("100.00"))
                .isActive(true)
                .build();

        EmployeeSalaryComponentDto result = service.assignComponentToEmployee(employeeId, dto);

        assertThat(result).isNotNull();
        assertThat(result.getEmployeeId()).isEqualTo(employeeId);
        assertThat(result.getSalaryComponentId()).isEqualTo(componentId);
        assertThat(result.getOverrideAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void assignComponentToEmployee_DuplicateComponent_ThrowsException() {
        EmployeeSalaryComponent existing = new EmployeeSalaryComponent();
        existing.setEmployee(employee);
        existing.setSalaryComponent(salaryComponent);

        when(employeeRepository.findByIdAndClientIdAndOrgId(employeeId, clientId, orgId))
                .thenReturn(Optional.of(employee));
        when(salaryComponentRepository.findByIdAndClientIdAndOrgIdOrGlobal(componentId, clientId, orgId))
                .thenReturn(Optional.of(salaryComponent));
        when(employeeSalaryComponentRepository.findAllByEmployeeIdAndSalaryComponentId(employeeId, componentId))
                .thenReturn(List.of(existing));

        EmployeeSalaryComponentDto newDto = EmployeeSalaryComponentDto.builder()
                .salaryComponentId(componentId)
                .overrideAmount(new BigDecimal("100.00"))
                .isActive(true)
                .build();

        assertThatThrownBy(() -> service.assignComponentToEmployee(employeeId, newDto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Salary rule is already assigned to this employee.");
    }

    @Test
    void assignComponentToEmployee_UpdateExistingComponent_Success() {
        UUID existingAssignmentId = UUID.randomUUID();
        EmployeeSalaryComponent existing = new EmployeeSalaryComponent();
        existing.setId(existingAssignmentId);
        existing.setEmployee(employee);
        existing.setSalaryComponent(salaryComponent);

        when(employeeRepository.findByIdAndClientIdAndOrgId(employeeId, clientId, orgId))
                .thenReturn(Optional.of(employee));
        when(salaryComponentRepository.findByIdAndClientIdAndOrgIdOrGlobal(componentId, clientId, orgId))
                .thenReturn(Optional.of(salaryComponent));
        when(employeeSalaryComponentRepository.findAllByEmployeeIdAndSalaryComponentId(employeeId, componentId))
                .thenReturn(List.of(existing));
        when(employeeSalaryComponentRepository.save(any(EmployeeSalaryComponent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeSalaryComponentDto updateDto = EmployeeSalaryComponentDto.builder()
                .id(existingAssignmentId)
                .salaryComponentId(componentId)
                .overrideAmount(new BigDecimal("150.00"))
                .isActive(true)
                .build();

        EmployeeSalaryComponentDto result = service.assignComponentToEmployee(employeeId, updateDto);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(existingAssignmentId);
        assertThat(result.getOverrideAmount()).isEqualByComparingTo("150.00");
    }

    @Test
    void removeComponentFromEmployee_Success() {
        EmployeeSalaryComponent existing = new EmployeeSalaryComponent();
        existing.setEmployee(employee);
        existing.setSalaryComponent(salaryComponent);

        when(employeeRepository.findByIdAndClientIdAndOrgId(employeeId, clientId, orgId))
                .thenReturn(Optional.of(employee));
        when(employeeSalaryComponentRepository.findAllByEmployeeIdAndSalaryComponentId(employeeId, componentId))
                .thenReturn(List.of(existing));

        service.removeComponentFromEmployee(employeeId, componentId);

        verify(employeeSalaryComponentRepository, times(1)).deleteAll(List.of(existing));
    }
}
