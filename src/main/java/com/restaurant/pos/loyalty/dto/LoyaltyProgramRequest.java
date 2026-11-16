package com.restaurant.pos.loyalty.dto;

import jakarta.validation.constraints.DecimalMin;
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
    @DecimalMin(value = "0.01", message = "Spend amount must be greater than zero")
    private BigDecimal spendAmount;

    @Min(1)
    private int earnPoints;

    // Redemption rule
    @Min(1)
    private int pointsRequired;

    @NotNull
    @DecimalMin(value = "0.01", message = "Discount amount must be greater than zero")
    private BigDecimal discountAmount;

    @Builder.Default
    private int minPoints = 0;

    @Min(value = 1, message = "Max points per order must be at least 1")
    private Integer maxPointsPerOrder;

    @Builder.Default
    private boolean allowPartial = true;
}
