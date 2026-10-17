package com.restaurant.pos.founder.lead.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeadActivityDto {
    private UUID id;
    private UUID leadId;
    private String activityType; // CALL, WHATSAPP, DEMO, NOTE, STATUS_CHANGE, PAYMENT_RECORDED
    private String description;
    private LocalDateTime createdAt;
}
