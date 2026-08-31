package com.restaurant.pos.review.domain;

import com.restaurant.pos.common.entity.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table(name = "reviews")
public class Review extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(nullable = false)
    private Integer rating;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Builder.Default
    @Column(name = "is_approved", length = 1)
    private String isApproved = "Y";

    public boolean isApproved() {
        return "Y".equalsIgnoreCase(isApproved);
    }
}
