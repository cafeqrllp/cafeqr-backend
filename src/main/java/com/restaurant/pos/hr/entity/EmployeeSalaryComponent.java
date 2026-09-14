package com.restaurant.pos.hr.entity;

import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "hr_employee_salary_components")
@Getter
@Setter
@NoArgsConstructor
public class EmployeeSalaryComponent extends BaseEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salary_component_id", nullable = false)
    private SalaryComponent salaryComponent;

    @Column(name = "override_amount", precision = 19, scale = 2)
    private BigDecimal overrideAmount;

    @Column(name = "override_percentage", precision = 5, scale = 2)
    private BigDecimal overridePercentage;

    @Column(name = "is_active")
    private boolean isActive = true;
}
