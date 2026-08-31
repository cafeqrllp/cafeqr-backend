package com.restaurant.pos.credit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCreditCustomerRequest {
    private String name;
    private String phone;
    private String email;
    private BigDecimal creditLimit;
    private BigDecimal openingBalance;
    private String notes;
}
