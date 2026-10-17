package com.restaurant.pos.founder.lead.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "founder_leads")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FounderLead {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "business_name")
    private String businessName;

    @Column(name = "primary_phone", nullable = false)
    private String primaryPhone;

    @Column(name = "whatsapp_number")
    private String whatsappNumber;

    @Column(name = "alternate_phone")
    private String alternatePhone;

    private String email;
    private String city;

    @Builder.Default
    @Column(nullable = false)
    private String source = "PHONE_CALL"; // WHATSAPP, INSTAGRAM, FACEBOOK, PHONE_CALL, WEBSITE, WALK_IN, REFERRAL

    @Builder.Default
    @Column(nullable = false)
    private String status = "YET_TO_REACH_OUT"; // YET_TO_REACH_OUT, NEW_INQUIRY, IN_DISCUSSION, DEMO_SCHEDULED, ONBOARDED_TRIAL, PAID_ACTIVE, COLD_FOLLOW_UP, LOST

    @Builder.Default
    private String temperature = "WARM"; // HOT, WARM, COLD

    @Builder.Default
    @Column(name = "deal_value_estimate_paise")
    private Long dealValueEstimatePaise = 0L;

    @Column(name = "assigned_org_id")
    private UUID assignedOrgId;

    @Column(name = "assigned_client_id")
    private UUID assignedClientId;

    @Column(name = "next_follow_up_at")
    private LocalDateTime nextFollowUpAt;

    @Column(name = "follow_up_note", length = 500)
    private String followUpNote;

    @Column(name = "last_contacted_at")
    private LocalDateTime lastContactedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "lost_reason")
    private String lostReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.source == null) this.source = "PHONE_CALL";
        if (this.status == null) this.status = "YET_TO_REACH_OUT";
        if (this.temperature == null) this.temperature = "WARM";
        if (this.dealValueEstimatePaise == null) this.dealValueEstimatePaise = 0L;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
