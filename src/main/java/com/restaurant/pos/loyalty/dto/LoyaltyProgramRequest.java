package com.restaurant.pos.loyalty.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyProgramRequest {

    @NotBlank
    private String name;

    private String description;

    @Builder.Default
    private boolean isActive = true;

    @Builder.Default
    private boolean isDefault = false;

    @Builder.Default
    private int priority = 10;

    // Earn rule
    @NotNull
    private BigDecimal spendAmount;

    @Min(1)
    private int earnPoints;

    // Redemption rule
    @Min(1)
    private int pointsRequired;

    @NotNull
    private BigDecimal discountAmount;

    @Builder.Default
    private int minPoints = 0;

    private Integer maxPointsPerOrder;

    @Builder.Default
    private boolean allowPartial = true;
}
