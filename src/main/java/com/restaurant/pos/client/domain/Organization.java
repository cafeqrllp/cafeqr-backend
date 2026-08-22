package com.restaurant.pos.client.domain;

import com.restaurant.pos.common.entity.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table(name = "organizations")
public class Organization extends AuditableEntity {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "client_id", updatable = false)
    private UUID clientId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", insertable = false, updatable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Client client;

    private String name;
    
    @Column(length = 100)
    private String slug;

    private String orgCode;
    
    @Builder.Default
    @Column(name = "branch_code", length = 20, nullable = false)
    private String branchCode = "HQ";
    
    private String address;
    private String phone;
    private String email;
    private String gstin;
    private String timezone;
    
    @Column(name = "pos_type", length = 50)
    private String posType;
    
    @Builder.Default
    @Column(name = "isactive", length = 1)
    @com.fasterxml.jackson.annotation.JsonProperty("isactive")
    private String isactive = "Y"; // Standardized Y/N

    @Column(columnDefinition = "TEXT")
    private String logoUrl;
    
    @Column(name = "banner_url", columnDefinition = "TEXT")
    private String bannerUrl;

    private String googleMapsUrl;
    private String pinCode;
    private Double latitude;
    private Double longitude;
    private Double deliveryRadiusKm;

    private String subscriptionStatus;
    private java.time.LocalDateTime subscriptionExpiryDate;

    public boolean isActive() {
        return "Y".equalsIgnoreCase(isactive);
    }

    public void setActive(boolean active) {
        this.isactive = active ? "Y" : "N";
    }

    public boolean isSubscriptionActive() {
        if (!isActive()) {
            return false;
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String status = subscriptionStatus == null ? "EXPIRED" : subscriptionStatus.trim().toUpperCase();
        boolean isTrialOrActive = "TRIAL".equals(status) || "ACTIVE".equals(status);
        return isTrialOrActive && subscriptionExpiryDate != null && !subscriptionExpiryDate.isBefore(now);
    }
}
