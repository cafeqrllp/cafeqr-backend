package com.restaurant.pos.review.dto;

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
public class ReviewResponseDto {
    private UUID id;
    private UUID clientId;
    private UUID orgId;
    private String customerName;
    private String customerEmail;
    private Integer rating;
    private String comment;
    private Instant createdAt;
}
