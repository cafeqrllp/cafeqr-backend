package com.restaurant.pos.inventory.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "stock_transfer_lines")
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class StockTransferLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id")
    @JsonIgnore
    @ToString.Exclude
    private StockTransfer transfer;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "variant_id")
    private UUID variantId;

    @Column(name = "transfer_quantity", nullable = false, precision = 15, scale = 3)
    private BigDecimal transferQuantity;

    @Transient
    private String productName;

    @Transient
    private String variantName;

    @Transient
    private String sku;

    @Transient
    private String categoryName;

    @Transient
    private BigDecimal unitCost;

    @Builder.Default
    @JsonProperty("isActive")
    @Column(name = "isactive", length = 1)
    private String isActive = "Y";
}


