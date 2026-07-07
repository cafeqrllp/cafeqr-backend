package com.restaurant.pos.purchasing.domain;

import com.restaurant.pos.common.entity.BaseEntity;
import com.restaurant.pos.product.domain.Product;
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
@EqualsAndHashCode(callSuper = true, exclude = {"pricelist", "product"})
@Table(name = "pricelist_products")
public class PricelistProduct extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pricelist_id", nullable = false)
    @JsonIgnore
    private Pricelist pricelist;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @JsonIgnore
    private Product product;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "org_id")
    private UUID orgId;

    @Builder.Default
    @JsonProperty("isActive")
    @Column(name = "isactive", length = 1)
    private String isActive = "Y";
    
    // Helper to get pricelist ID for JSON
    @JsonProperty("pricelistId")
    public UUID getPricelistId() {
        return pricelist != null ? pricelist.getId() : null;
    }

    @JsonProperty("pricelistId")
    public void setPricelistId(UUID pricelistId) {
        if (pricelistId != null) {
            this.pricelist = Pricelist.builder().id(pricelistId).build();
        }
    }

    @JsonProperty("pricelistName")
    public String getPricelistName() {
        return pricelist != null ? pricelist.getName() : null;
    }
}
