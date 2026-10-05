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
import com.restaurant.pos.hr.event.HrEmployeeDeletedEvent;
import com.restaurant.pos.hr.event.HrEmployeeSavedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final DesignationRepository designationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<EmployeeDto> getAllEmployees() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        return employeeRepository.findByClientIdAndOrgId(clientId, orgId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public EmployeeDto getEmployeeById(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        Employee employee = employeeRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new BusinessException("Employee not found"));
        return mapToDto(employee);
    }

    @Transactional
    public EmployeeDto createEmployee(EmployeeDto dto) {
        validateEmployeeDto(dto);
        validateDuplicateEmployee(dto, null);
        Employee employee = new Employee();
        mapToEntity(dto, employee);
        Employee saved = employeeRepository.save(employee);
        EmployeeDto resultDto = mapToDto(saved);
        
        if (eventPublisher != null) {
            UUID clientId = TenantContext.getCurrentTenant();
            UUID orgId = TenantContext.getCurrentOrg();
            eventPublisher.publishEvent(new HrEmployeeSavedEvent(this, resultDto, clientId, orgId, "CREATED"));
        }
        
        return resultDto;
    }

    @Transactional
    public EmployeeDto updateEmployee(UUID id, EmployeeDto dto) {
        validateEmployeeDto(dto);
        validateDuplicateEmployee(dto, id);
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        Employee employee = employeeRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new BusinessException("Employee not found"));

        mapToEntity(dto, employee);
        Employee saved = employeeRepository.save(employee);
        EmployeeDto resultDto = mapToDto(saved);
        
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new HrEmployeeSavedEvent(this, resultDto, clientId, orgId, "UPDATED"));
        }
        
        return resultDto;
    }

    private void validateDuplicateEmployee(EmployeeDto dto, UUID excludeId) {
        UUID clientId = TenantContext.getCurrentTenant();
        if (clientId == null) return;

        if (dto.getEmail() != null && !dto.getEmail().isBlank()) {
            String email = dto.getEmail().trim();
            if (employeeRepository.existsByEmailAndClientId(email, clientId, excludeId)) {
                throw new BusinessException("An employee with the email address '" + email + "' already exists.");
            }
        }

        if (dto.getFirstName() != null && !dto.getFirstName().isBlank()) {
            String firstName = dto.getFirstName().trim();
            String lastName = dto.getLastName() != null ? dto.getLastName().trim() : "";
            if (employeeRepository.existsByFirstNameAndLastNameAndClientId(firstName, lastName, clientId, excludeId)) {
                String fullName = (firstName + " " + lastName).trim();
                throw new BusinessException("An employee named '" + fullName + "' already exists in the system.");
            }
        }
    }

    private void validateEmployeeDto(EmployeeDto dto) {
        if (dto.getFirstName() == null || dto.getFirstName().trim().isEmpty()) {
            throw new BusinessException("First name is required.");
        }

        if (dto.getEmail() != null && !dto.getEmail().isBlank()) {
            String email = dto.getEmail().trim();
            if (!email.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                throw new BusinessException("Invalid email format.");
            }
        }

        if (dto.getPinCode() != null && !dto.getPinCode().isBlank()) {
            String cleanPin = dto.getPinCode().trim();
            if (!cleanPin.matches("\\d{4}")) {
                throw new BusinessException("Kiosk PIN must be exactly 4 numeric digits.");
            }
        }

        if (dto.getBaseSalary() != null && dto.getBaseSalary().compareTo(java.math.BigDecimal.ZERO) < 0) {
            throw new BusinessException("Base salary cannot be negative.");
        }

        if (dto.getHourlyRate() != null && dto.getHourlyRate().compareTo(java.math.BigDecimal.ZERO) < 0) {
            throw new BusinessException("Hourly rate cannot be negative.");
        }

        if (dto.getBankAccountNumber() != null && !dto.getBankAccountNumber().isBlank()) {
            if (!dto.getBankAccountNumber().trim().matches("^\\d+$")) {
                throw new BusinessException("Bank Account Number must contain only digits.");
            }
        }

        if (dto.getBankRoutingNumber() != null && !dto.getBankRoutingNumber().isBlank()) {
            if (!dto.getBankRoutingNumber().trim().matches("^\\d{9}$")) {
                throw new BusinessException("Bank Routing Number must be exactly 9 digits.");
            }
        }
    }

    @Transactional
    public void deleteEmployee(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        
        Employee employee = employeeRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new BusinessException("Employee not found"));
                
        try {
            employeeRepository.delete(employee);
            employeeRepository.flush();
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new HrEmployeeDeletedEvent(this, employee.getId(), employee.getUserId(), employee.getEmail(), clientId, orgId));
            }
        } catch (DataIntegrityViolationException e) {
            String name = (employee.getFirstName() + " " + (employee.getLastName() != null ? employee.getLastName() : "")).trim();
            throw new BusinessException("Cannot delete employee '" + name + "' because they have linked attendance, leave, or payroll history. You can edit the employee and set their status to Inactive instead.");
        }
    }

    private void mapToEntity(EmployeeDto dto, Employee employee) {
        employee.setFirstName(dto.getFirstName());
        employee.setLastName(dto.getLastName());
        employee.setEmail(dto.getEmail());
        employee.setPhoneNumber(dto.getPhoneNumber());
        employee.setDateOfJoining(dto.getDateOfJoining());
        employee.setDateOfBirth(dto.getDateOfBirth());
        employee.setGender(dto.getGender());
        employee.setUserId(dto.getUserId());
        employee.setBaseSalary(dto.getBaseSalary());
        employee.setHourlyRate(dto.getHourlyRate());
        employee.setEmploymentType(dto.getEmploymentType());
        employee.setBankName(dto.getBankName());
        employee.setBankAccountNumber(dto.getBankAccountNumber());
        employee.setBankRoutingNumber(dto.getBankRoutingNumber());
        employee.setPinCode(dto.getPinCode());
        employee.setActive(dto.isActive());

        if (dto.getDepartmentId() != null) {
            Department department = departmentRepository.findById(dto.getDepartmentId())
                    .orElseThrow(() -> new BusinessException("Department not found"));
            employee.setDepartment(department);
        } else {
            employee.setDepartment(null);
        }

        if (dto.getDesignationId() != null) {
            Designation designation = designationRepository.findById(dto.getDesignationId())
                    .orElseThrow(() -> new BusinessException("Designation not found"));
            employee.setDesignation(designation);
        } else {
            employee.setDesignation(null);
        }
    }

    private EmployeeDto mapToDto(Employee entity) {
        EmployeeDto dto = EmployeeDto.builder()
                .id(entity.getId())
                .firstName(entity.getFirstName())
                .lastName(entity.getLastName())
                .email(entity.getEmail())
                .phoneNumber(entity.getPhoneNumber())
                .dateOfJoining(entity.getDateOfJoining())
                .dateOfBirth(entity.getDateOfBirth())
                .gender(entity.getGender())
                .userId(entity.getUserId())
                .baseSalary(entity.getBaseSalary())
                .hourlyRate(entity.getHourlyRate())
                .employmentType(entity.getEmploymentType())
                .bankName(entity.getBankName())
                .bankAccountNumber(entity.getBankAccountNumber())
                .bankRoutingNumber(entity.getBankRoutingNumber())
                .pinCode(entity.getPinCode())
                .isActive(entity.isActive())
                .build();
                
        if (entity.getDepartment() != null) {
            try {
                dto.setDepartmentId(entity.getDepartment().getId());
                dto.setDepartmentName(entity.getDepartment().getName());
            } catch (Exception ignored) {
                dto.setDepartmentId(null);
                dto.setDepartmentName(null);
            }
        }
        
        if (entity.getDesignation() != null) {
            try {
                dto.setDesignationId(entity.getDesignation().getId());
                dto.setDesignationName(entity.getDesignation().getName());
            } catch (Exception ignored) {
                dto.setDesignationId(null);
                dto.setDesignationName(null);
            }
        }
        
        return dto;
    }
}
