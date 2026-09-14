package com.restaurant.pos.hr.entity;

import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "hr_employees")
@Getter
@Setter
@NoArgsConstructor
public class Employee extends BaseEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "email")
    private String email;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "date_of_joining")
    private LocalDate dateOfJoining;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;
    
    @Column(name = "gender")
    private String gender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    @NotFound(action = NotFoundAction.IGNORE)
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "designation_id")
    @NotFound(action = NotFoundAction.IGNORE)
    private Designation designation;

    // Optional link to POS User account
    @Column(name = "user_id")
    private UUID userId;

    // Payroll basic info
    @Column(name = "base_salary", precision = 10, scale = 2)
    private BigDecimal baseSalary = BigDecimal.ZERO;

    @Column(name = "hourly_rate", precision = 10, scale = 2)
    private BigDecimal hourlyRate = BigDecimal.ZERO;
    
    @Column(name = "employment_type")
    private String employmentType; // e.g., HOURLY, SALARIED, COMMISSION

    // Bank Details
    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "bank_account_number")
    private String bankAccountNumber;

    @Column(name = "bank_routing_number")
    private String bankRoutingNumber;

    @Column(name = "is_active")
    private boolean isActive = true;

    // Statutory Info
    @Column(name = "tax_id")
    private String taxId;

    @Column(name = "national_id")
    private String nationalId;

    @Column(name = "pin_code")
    private String pinCode;
}
