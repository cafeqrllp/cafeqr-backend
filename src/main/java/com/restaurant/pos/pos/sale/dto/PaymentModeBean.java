package com.restaurant.pos.pos.sale.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Dedicated payment mode bean for the POS Sales Screen settlement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentModeBean {
    private UUID id;
    private String displayName;
    private String paymentType;
    private boolean isDefault;
    private Integer sortOrder;
}
