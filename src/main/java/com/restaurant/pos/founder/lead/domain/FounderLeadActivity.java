package com.restaurant.pos.founder.lead.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "founder_lead_activities")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FounderLeadActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    @Column(name = "activity_type", nullable = false)
    private String activityType; // CALL, WHATSAPP, DEMO, NOTE, STATUS_CHANGE, PAYMENT_RECORDED

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
