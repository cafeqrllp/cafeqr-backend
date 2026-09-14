package com.restaurant.pos.hr.dto;

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
public class SalarySlipDto {
    private UUID id;
    private UUID employeeId;
    private String employeeName;
    private UUID payrollRunId;
    private BigDecimal totalWorkedHours;
    private Integer totalUnpaidLeaveDays;
    private BigDecimal grossPay;
    private BigDecimal totalDeductions;
    private BigDecimal netPay;
    private String status;
}
