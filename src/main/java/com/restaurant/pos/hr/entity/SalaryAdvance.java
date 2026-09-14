package com.restaurant.pos.hr.entity;

import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "hr_salary_advances")
@Getter
@Setter
@NoArgsConstructor
public class SalaryAdvance extends BaseEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "advance_date", nullable = false)
    private LocalDate advanceDate;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "monthly_installment_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyInstallmentAmount;

    @Column(name = "remaining_balance", nullable = false, precision = 10, scale = 2)
    private BigDecimal remainingBalance;

    @Column(name = "reason")
    private String reason;

    @Column(name = "status", nullable = false)
    private String status = "PENDING"; // PENDING, APPROVED, REJECTED, PAID, COMPLETED
}
