package com.restaurant.pos.pos.sale.query;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Concrete DTO implementing {@link PosCustomerSummaryView} for Redis caching and serialization.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PosCustomerSummaryDto implements PosCustomerSummaryView {
    private UUID id;
    private String name;
    private String phone;
    private String email;
    private String address;
    private String gstNumber;
    private String customerCategory;
    private Integer loyaltyPoints;
    private BigDecimal creditLimit;
    private BigDecimal balance;

    public static PosCustomerSummaryDto from(PosCustomerSummaryView v) {
        if (v == null) return null;
        return PosCustomerSummaryDto.builder()
                .id(v.getId())
                .name(v.getName())
                .phone(v.getPhone())
                .email(v.getEmail())
                .address(v.getAddress())
                .gstNumber(v.getGstNumber())
                .customerCategory(v.getCustomerCategory())
                .loyaltyPoints(v.getLoyaltyPoints())
                .creditLimit(v.getCreditLimit())
                .balance(v.getBalance())
                .build();
    }
}
