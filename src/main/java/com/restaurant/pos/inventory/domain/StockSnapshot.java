package com.restaurant.pos.inventory.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "stock_snapshots", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"warehouse_id", "product_id", "variant_id"})
})
public class StockSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "variant_id")
    private UUID variantId;

    @Builder.Default
    @Column(name = "current_quantity", precision = 15, scale = 3)
    private BigDecimal currentQuantity = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "last_updated")
    private LocalDateTime lastUpdated = LocalDateTime.now();
    
    // We do not inherit AuditableEntity because this is just a cache representation

    @Transient
    private String variantOptionName;

    @Transient
    private java.math.BigDecimal variantCostPrice;
}
