package com.restaurant.pos.client.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.restaurant.pos.common.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Table(name = "clients")
public class Client extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = jakarta.persistence.GenerationType.UUID)
    @Builder.Default
    private UUID id = null; 

    private String name;
    
    @Column(length = 100, unique = true)
    private String slug;

    private String legalName;
    private String ownerName;
    private String email;
    private String phone;
    private String panNumber;
    private String gstNumber;
    private String fssaiNumber;
    private String website;
    private String address;

    @Builder.Default
    @Column(name = "isactive", length = 1)
    private String isactive = "Y"; 

    private String subscriptionStatus; 
    private String stripeCustomerId;
    
    private String country;
    private String posType; 
    private LocalDateTime subscriptionExpiryDate;

    private String currency; 
    @Column(columnDefinition = "TEXT")
    private String logoUrl;
    @Column(name = "banner_url", columnDefinition = "TEXT")
    private String bannerUrl;
    private String brandColor; 
    private String timezone;
    
    @Builder.Default
    private String primaryLanguage = "English";

    private String googleMapsUrl;
    private String pinCode;

    // Social Media & Engagement
    private String instagramUrl;
    private String facebookUrl;
    private String whatsappNumber;

    private String bankName;
    private String accountNumber;
    private String ifscCode;

    // Explicit Getters and Setters (Fallback for Lombok in certain environments)
    public String getInstagramUrl() { return instagramUrl; }
    public void setInstagramUrl(String instagramUrl) { this.instagramUrl = instagramUrl; }
    
    public String getFacebookUrl() { return facebookUrl; }
    public void setFacebookUrl(String facebookUrl) { this.facebookUrl = facebookUrl; }
    
    public String getWhatsappNumber() { return whatsappNumber; }
    public void setWhatsappNumber(String whatsappNumber) { this.whatsappNumber = whatsappNumber; }

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
