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
public class FounderLeadDto {
    private UUID id;
    private String name;
    private String businessName;
    private String primaryPhone;
    private String whatsappNumber;
    private String alternatePhone;
    private String email;
    private String city;
    private String source; // WHATSAPP, INSTAGRAM, FACEBOOK, PHONE_CALL, WEBSITE, WALK_IN, REFERRAL
    private String status; // YET_TO_REACH_OUT, NEW_INQUIRY, IN_DISCUSSION, DEMO_SCHEDULED, ONBOARDED_TRIAL, PAID_ACTIVE, COLD_FOLLOW_UP, LOST
    private String temperature; // HOT, WARM, COLD
    private Long dealValueEstimatePaise;
    private UUID assignedOrgId;
    private UUID assignedClientId;
    private LocalDateTime nextFollowUpAt;
    private String followUpNote;
    private LocalDateTime lastContactedAt;
    private String notes;
    private String lostReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Associated branch/client details resolved automatically
    private String matchedBranchName;
    private String matchedClientName;
    private String matchedSubscriptionStatus;
}
