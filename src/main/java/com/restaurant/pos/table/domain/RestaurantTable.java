package com.restaurant.pos.table.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
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
@Table(name = "restaurant_tables")
public class RestaurantTable extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "table_number", nullable = false, length = 20)
    private String tableNumber;

    @Column(length = 100)
    private String name;

    @Builder.Default
    @Column(name = "seating_capacity")
    private Integer seatingCapacity = 4;

    @Column(length = 50)
    private String floor;

    @Column(length = 50)
    private String section;

    @Column(length = 50)
    private String shape;

    @Builder.Default
    @Column(length = 20)
    private String status = "AVAILABLE"; // AVAILABLE, OCCUPIED, RESERVED, MAINTENANCE

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Builder.Default
    @Column(name = "display_order")
    private Integer displayOrder = 0;

    @Builder.Default
    @JsonProperty("isActive")
    @Column(name = "isactive", length = 1)
    private String isactive = "Y";
}
