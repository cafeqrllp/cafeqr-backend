package com.restaurant.pos.hr.entity;

import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "hr_leave_balances")
@Getter
@Setter
@NoArgsConstructor
public class LeaveBalance extends BaseEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "leave_type", nullable = false)
    private String leaveType; // SICK, VACATION, CASUAL

    @Column(name = "total_allocated", nullable = false)
    private int totalAllocated;

    @Column(name = "total_used")
    private int totalUsed = 0;

    @Column(name = "year", nullable = false)
    private int year;
}
