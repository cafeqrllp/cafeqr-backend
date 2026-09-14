package com.restaurant.pos.hr.entity;

import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "hr_payroll_runs")
@Getter
@Setter
@NoArgsConstructor
public class PayrollRun extends BaseEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "name", nullable = false)
    private String name; // e.g., "September 2026 Payroll"

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "status", nullable = false)
    private String status = "DRAFT"; // DRAFT, PROCESSING, COMPLETED, PAID
}
