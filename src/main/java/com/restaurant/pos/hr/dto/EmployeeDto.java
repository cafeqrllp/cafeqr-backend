package com.restaurant.pos.hr.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeDto {
    private UUID id;
    private String firstName;
    private String lastName;
    private String email;
    private String phoneNumber;
    private LocalDate dateOfJoining;
    private LocalDate dateOfBirth;
    private String gender;

    private UUID departmentId;
    private String departmentName;

    private UUID designationId;
    private String designationName;

    private UUID userId;

    private BigDecimal baseSalary;
    private BigDecimal hourlyRate;
    private String employmentType;

    private String bankName;
    private String bankAccountNumber;
    private String bankRoutingNumber;

    private String pinCode;

    @JsonProperty("isActive")
    private boolean isActive;
}
