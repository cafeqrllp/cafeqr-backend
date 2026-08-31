package com.restaurant.pos.loyalty.domain;

import com.restaurant.pos.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A named loyalty programme that defines how customers earn and redeem points.
 * One programme per (client, org) may be marked as the default.
 * The uniqueness of the default flag is enforced at the DB level via a partial
 * unique index: {@code ux_loyalty_program_client_org_default}.
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table(name = "loyalty_program")
public class LoyaltyProgram extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    /** Higher priority wins during automatic programme selection. */
    @Builder.Default
    @Column(nullable = false)
    private int priority = 10;

    @Builder.Default
    @OneToMany(mappedBy = "program", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<LoyaltyEarnRule> earnRules = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "program", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<LoyaltyRedemptionRule> redemptionRules = new ArrayList<>();
}
