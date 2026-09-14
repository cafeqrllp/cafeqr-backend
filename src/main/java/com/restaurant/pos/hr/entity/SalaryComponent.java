package com.restaurant.pos.hr.entity;

import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "hr_salary_components")
@Getter
@Setter
@NoArgsConstructor
public class SalaryComponent extends BaseEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "name", nullable = false)
    private String name; // e.g., "Basic Pay", "Overtime", "Income Tax", "Health Insurance"

    @Column(name = "type", nullable = false)
    private String type; // EARNING or DEDUCTION

    @Column(name = "is_tax_applicable")
    private boolean isTaxApplicable = false;
    
    @Column(name = "depends_on_attendance")
    private boolean dependsOnAttendance = false;

    // Rules Engine properties
    @Column(name = "amount_type", nullable = false)
    private String amountType; // FIXED or PERCENTAGE

    @Column(name = "default_amount", precision = 10, scale = 2)
    private BigDecimal defaultAmount;

    @Column(name = "percentage", precision = 5, scale = 2)
    private BigDecimal percentage; // If type is PERCENTAGE, e.g., 15.00 for 15%
    
    @Column(name = "percentage_of_component")
    private String percentageOfComponent; // Which component is this a percentage of? Usually "Basic Pay" or "Gross Pay"

    @Column(name = "is_active")
    private boolean isActive = true;
}
