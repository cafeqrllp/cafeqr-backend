package com.restaurant.pos.loyalty.command;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class UpdateLoyaltyProgramCommand {

    private UUID id;

    @NotBlank
    private String name;

    private String description;

    @JsonProperty("isActive")
    @Builder.Default
    private boolean isActive = true;

    @JsonProperty("isDefault")
    @Builder.Default
    private boolean isDefault = false;

    @JsonProperty("active")
    public void setActiveAlias(boolean active) {
        this.isActive = active;
    }

    @JsonProperty("default")
    public void setDefaultAlias(boolean isDefault) {
        this.isDefault = isDefault;
    }

    @Builder.Default
    private int priority = 10;

    @NotNull
    private BigDecimal spendAmount;

    @Min(1)
    private int earnPoints;

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
