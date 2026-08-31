package com.restaurant.pos.loyalty.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerLoyaltyDto {

    private UUID id;
    private UUID customerId;
    private String customerName;
    private String customerPhone;
    private UUID programId;
    private String programName;
    private int currentPoints;
    private int lifetimeEarned;
    private int lifetimeRedeemed;
    private Instant createdAt;
    private Instant updatedAt;
}
