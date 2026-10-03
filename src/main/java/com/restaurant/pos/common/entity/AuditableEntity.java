package com.restaurant.pos.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@MappedSuperclass
public abstract class AuditableEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @Column(name = "updated_by")
    private String updatedBy;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        }
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        }
        
        String currentUser = resolveCurrentUser();
        
        // Only set if not already manually set
        if (this.createdBy == null) {
            this.createdBy = currentUser;
        }
        if (this.updatedBy == null) {
            this.updatedBy = currentUser;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        String currentUser = resolveCurrentUser();
        if (!"SYSTEM".equals(currentUser)) {
            // Preserve customer user if customer was the creator or last editor
            boolean isUpdatedByCustomer = this.updatedBy != null && (this.updatedBy.endsWith("(customer)") || this.updatedBy.contains("(customer)"));
            boolean isCreatedByCustomer = this.createdBy != null && (this.createdBy.endsWith("(customer)") || this.createdBy.contains("(customer)"));
            if (!isUpdatedByCustomer && !isCreatedByCustomer) {
                this.updatedBy = currentUser;
            }
        } else if (this.updatedBy == null) {
            this.updatedBy = "SYSTEM";
        }
    }

    private String resolveCurrentUser() {
        try {
            java.util.UUID userId = com.restaurant.pos.common.util.SecurityUtils.getCurrentUserId();
            if (userId != null) {
                return userId.toString();
            }
        } catch (Exception ignored) {
            // Fall through to SYSTEM
        }
        return "SYSTEM";
    }
}
