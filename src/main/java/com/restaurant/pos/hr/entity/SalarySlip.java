package com.restaurant.pos.hr.entity;

import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "hr_salary_slips")
@Getter
@Setter
@NoArgsConstructor
public class SalarySlip extends BaseEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_run_id", nullable = false)
    private PayrollRun payrollRun;

    @Column(name = "total_worked_hours", precision = 5, scale = 2)
    private BigDecimal totalWorkedHours = BigDecimal.ZERO;

    @Column(name = "total_unpaid_leave_days")
    private Integer totalUnpaidLeaveDays = 0;

    @Column(name = "gross_pay", nullable = false, precision = 10, scale = 2)
    private BigDecimal grossPay = BigDecimal.ZERO;

    @Column(name = "total_deductions", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalDeductions = BigDecimal.ZERO;

    @Column(name = "net_pay", nullable = false, precision = 10, scale = 2)
    private BigDecimal netPay = BigDecimal.ZERO;

    @Column(name = "status", nullable = false)
    private String status = "DRAFT"; // DRAFT, GENERATED, PAID
}
