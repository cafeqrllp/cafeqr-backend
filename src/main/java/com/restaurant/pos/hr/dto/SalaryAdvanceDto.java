package com.restaurant.pos.hr.dto;

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
public class SalaryAdvanceDto {
    private UUID id;
    private UUID employeeId;
    private String employeeName;
    private LocalDate advanceDate;
    private BigDecimal totalAmount;
    private BigDecimal monthlyInstallmentAmount;
    private BigDecimal remainingBalance;
    private String reason;
    private String status;
}
