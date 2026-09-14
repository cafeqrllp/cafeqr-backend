package com.restaurant.pos.pos.sale.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Dedicated customer quick search bean for the POS Sales Screen.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSearchBean {
    private UUID id;
    private String name;
    private String phone;
    private String email;
    private Boolean isCreditCustomer;
    private BigDecimal creditLimit;
    private BigDecimal balance;
    private Integer loyaltyPoints;
}
