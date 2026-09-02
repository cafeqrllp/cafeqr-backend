package com.restaurant.pos.loyalty.dto;

import com.restaurant.pos.loyalty.domain.LoyaltyTransactionType;
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
public class LoyaltyTransactionDto {

    private UUID id;
    private UUID customerId;
    private UUID orderId;
    private String orderNumber;
    private UUID programId;
    private String programName;
    private LoyaltyTransactionType transactionType;
    private int points;
    private int balanceAfter;
    private UUID referenceTransactionId;
    private String remarks;
    private Instant createdAt;
}
