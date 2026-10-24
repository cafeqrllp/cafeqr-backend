package com.restaurant.pos.product.domain;

import com.restaurant.pos.common.entity.AuditableEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table(name = "uoms")
public class Uom extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;
    private String shortName;
    
    @Builder.Default
    @JsonProperty("isActive")
    private boolean isActive = true;

    @Builder.Default
    @JsonProperty("isDefault")
    private boolean isDefault = false;

    /** Highest allowed decimal places for a UOM (enforced in the service and the database). */
    public static final int MAX_PRECISION = 4;

    @Builder.Default
    private int uomPrecision = 0;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "org_id")
    private UUID orgId;
}
