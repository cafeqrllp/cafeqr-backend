package com.restaurant.pos.hr.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeSalaryComponentDto {
    private UUID id;
    private UUID employeeId;
    private UUID salaryComponentId;
    private String componentName;
    private String componentType; // EARNING or DEDUCTION
    private String amountType; // FIXED or PERCENTAGE
    private BigDecimal defaultAmount;
    private BigDecimal percentage;
    private BigDecimal overrideAmount;
    private BigDecimal overridePercentage;
    
    @Builder.Default
    @JsonProperty("isActive")
    private boolean isActive = true;
}
