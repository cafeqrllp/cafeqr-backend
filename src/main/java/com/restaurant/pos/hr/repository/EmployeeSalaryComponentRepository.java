package com.restaurant.pos.hr.repository;

import com.restaurant.pos.hr.entity.EmployeeSalaryComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeSalaryComponentRepository extends JpaRepository<EmployeeSalaryComponent, UUID> {
    
    @Query("SELECT e FROM EmployeeSalaryComponent e JOIN FETCH e.salaryComponent WHERE e.employee.id = :employeeId AND e.isActive = true")
    List<EmployeeSalaryComponent> findActiveByEmployeeId(@Param("employeeId") UUID employeeId);

    @Query("SELECT e FROM EmployeeSalaryComponent e JOIN FETCH e.salaryComponent WHERE e.employee.id = :employeeId")
    List<EmployeeSalaryComponent> findByEmployeeId(@Param("employeeId") UUID employeeId);

    Optional<EmployeeSalaryComponent> findByEmployeeIdAndSalaryComponentId(UUID employeeId, UUID salaryComponentId);
}
