package com.restaurant.pos.hr.service;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.hr.dto.EmployeeDto;
import com.restaurant.pos.hr.entity.Department;
import com.restaurant.pos.hr.entity.Designation;
import com.restaurant.pos.hr.entity.Employee;
import com.restaurant.pos.hr.repository.DepartmentRepository;
import com.restaurant.pos.hr.repository.DesignationRepository;
import com.restaurant.pos.hr.repository.EmployeeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmployeeServiceTest {

    private EmployeeRepository employeeRepository;
    private DepartmentRepository departmentRepository;
    private DesignationRepository designationRepository;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private EmployeeService service;

    private UUID clientId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        employeeRepository = mock(EmployeeRepository.class);
        departmentRepository = mock(DepartmentRepository.class);
        designationRepository = mock(DesignationRepository.class);
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        service = new EmployeeService(employeeRepository, departmentRepository, designationRepository, eventPublisher);

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
    void createEmployee_ValidData_Success() {
        when(employeeRepository.save(any(Employee.class)))
                .thenAnswer(inv -> {
                    Employee e = inv.getArgument(0);
                    e.setId(UUID.randomUUID());
                    return e;
                });

        EmployeeDto dto = EmployeeDto.builder()
                .firstName("John")
                .lastName("Doe")
                .email("john.doe@example.com")
                .phoneNumber("1234567890")
                .employmentType("FULL_TIME")
                .baseSalary(new BigDecimal("3500.00"))
                .hourlyRate(new BigDecimal("0.00"))
                .pinCode("1234")
                .bankAccountNumber("1234567890")
                .bankRoutingNumber("123456789")
                .isActive(true)
                .build();

        EmployeeDto created = service.createEmployee(dto);

        assertThat(created).isNotNull();
        assertThat(created.getFirstName()).isEqualTo("John");
        assertThat(created.getEmail()).isEqualTo("john.doe@example.com");
    }

    @Test
    void createEmployee_BlankFirstName_ThrowsBusinessException() {
        EmployeeDto dto = EmployeeDto.builder()
                .firstName("   ")
                .lastName("Doe")
                .email("john.doe@example.com")
                .build();

        assertThatThrownBy(() -> service.createEmployee(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("First name is required.");
    }

    @Test
    void createEmployee_InvalidEmailFormat_ThrowsBusinessException() {
        EmployeeDto dto = EmployeeDto.builder()
                .firstName("John")
                .lastName("Doe")
                .email("not-an-email")
                .build();

        assertThatThrownBy(() -> service.createEmployee(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid email format.");
    }

    @Test
    void createEmployee_NegativeBaseSalary_ThrowsBusinessException() {
        EmployeeDto dto = EmployeeDto.builder()
                .firstName("John")
                .email("john@example.com")
                .baseSalary(new BigDecimal("-100.00"))
                .build();

        assertThatThrownBy(() -> service.createEmployee(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Base salary cannot be negative.");
    }

    @Test
    void createEmployee_NegativeHourlyRate_ThrowsBusinessException() {
        EmployeeDto dto = EmployeeDto.builder()
                .firstName("John")
                .email("john@example.com")
                .hourlyRate(new BigDecimal("-15.00"))
                .build();

        assertThatThrownBy(() -> service.createEmployee(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Hourly rate cannot be negative.");
    }

    @Test
    void createEmployee_InvalidPinCode_ThrowsBusinessException() {
        EmployeeDto dto = EmployeeDto.builder()
                .firstName("John")
                .email("john@example.com")
                .pinCode("12")
                .build();

        assertThatThrownBy(() -> service.createEmployee(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Kiosk PIN must be exactly 4 numeric digits.");
    }

    @Test
    void createEmployee_InvalidBankAccountNumber_ThrowsBusinessException() {
        EmployeeDto dto = EmployeeDto.builder()
                .firstName("John")
                .email("john@example.com")
                .bankAccountNumber("ACCT-1234")
                .build();

        assertThatThrownBy(() -> service.createEmployee(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Bank Account Number must contain only digits.");
    }

    @Test
    void createEmployee_InvalidBankRoutingNumber_ThrowsBusinessException() {
        EmployeeDto dto = EmployeeDto.builder()
                .firstName("John")
                .email("john@example.com")
                .bankRoutingNumber("12345")
                .build();

        assertThatThrownBy(() -> service.createEmployee(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Bank Routing Number must be exactly 9 digits.");
    }

    @Test
    void createEmployee_DepartmentNotFound_ThrowsBusinessException() {
        UUID deptId = UUID.randomUUID();
        when(departmentRepository.findById(deptId)).thenReturn(Optional.empty());

        EmployeeDto dto = EmployeeDto.builder()
                .firstName("John")
                .email("john@example.com")
                .departmentId(deptId)
                .build();

        assertThatThrownBy(() -> service.createEmployee(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Department not found");
    }

    @Test
    void createEmployee_DesignationNotFound_ThrowsBusinessException() {
        UUID desigId = UUID.randomUUID();
        when(designationRepository.findById(desigId)).thenReturn(Optional.empty());

        EmployeeDto dto = EmployeeDto.builder()
                .firstName("John")
                .email("john@example.com")
                .designationId(desigId)
                .build();

        assertThatThrownBy(() -> service.createEmployee(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Designation not found");
    }

    @Test
    void createEmployee_DuplicateEmail_ThrowsBusinessException() {
        when(employeeRepository.existsByEmailAndClientId(eq("john@example.com"), any(UUID.class), any())).thenReturn(true);

        EmployeeDto dto = EmployeeDto.builder()
                .firstName("John")
                .lastName("Doe")
                .email("john@example.com")
                .build();

        assertThatThrownBy(() -> service.createEmployee(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("An employee with the email address 'john@example.com' already exists.");
    }

    @Test
    void createEmployee_DuplicateName_ThrowsBusinessException() {
        when(employeeRepository.existsByFirstNameAndLastNameAndClientId(eq("John"), eq("Doe"), any(UUID.class), any())).thenReturn(true);

        EmployeeDto dto = EmployeeDto.builder()
                .firstName("John")
                .lastName("Doe")
                .build();

        assertThatThrownBy(() -> service.createEmployee(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("An employee named 'John Doe' already exists in the system.");
    }
}
