package com.restaurant.pos.review.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRequestDto {
    private String customerName;
    private String customerEmail;
    private Integer rating;
    private String comment;
    private UUID orgId;
}
