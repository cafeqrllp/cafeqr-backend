package com.restaurant.pos.costing.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Current stock cost of a product (and variant) for one organisation.
 * Equivalent of ADempiere's M_Cost; the per-transaction cost detail lives on stock_ledgers.
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "product_costs")
public class ProductCost {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "variant_id")
    private UUID variantId;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "costing_method", nullable = false, length = 20)
    private CostingMethod costingMethod = CostingMethod.AVERAGE_PO;

    @Builder.Default
    @Column(name = "current_cost_price", nullable = false, precision = 19, scale = 6)
    private BigDecimal currentCostPrice = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "current_qty", nullable = false, precision = 15, scale = 3)
    private BigDecimal currentQty = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "cumulated_amt", nullable = false, precision = 19, scale = 6)
    private BigDecimal cumulatedAmt = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "cumulated_qty", nullable = false, precision = 15, scale = 3)
    private BigDecimal cumulatedQty = BigDecimal.ZERO;

    @Column(name = "last_purchase_price", precision = 19, scale = 6)
    private BigDecimal lastPurchasePrice;

    @Builder.Default
    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();
}
