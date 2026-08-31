package com.restaurant.pos.loyalty.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyProgramDto {

    private UUID id;
    private String name;
    private String description;

    @JsonProperty("isActive")
    private boolean isActive;

    @JsonProperty("isDefault")
    private boolean isDefault;

    private int priority;

    private EarnRuleDto earnRule;
    private RedemptionRuleDto redemptionRule;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @JsonProperty("active")
    public boolean getActive() {
        return isActive;
    }

    @JsonProperty("default")
    public boolean getDefault() {
        return isDefault;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EarnRuleDto {
        private UUID id;
        private BigDecimal spendAmount;
        private int earnPoints;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RedemptionRuleDto {
        private UUID id;
        private int pointsRequired;
        private BigDecimal discountAmount;
        private int minPoints;
        private Integer maxPointsPerOrder;
        private boolean allowPartial;
    }
}
