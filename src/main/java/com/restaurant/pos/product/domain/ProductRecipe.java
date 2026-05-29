package com.restaurant.pos.product.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.restaurant.pos.common.entity.AuditableEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table(name = "product_recipes", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"product_id", "ingredient_id"})
})
public class ProductRecipe extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @JsonIgnore
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id", nullable = false)
    @JsonIgnoreProperties({ "category", "uom", "variantMappings", "variantPricings", "upsells" })
    private Product ingredient;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantity;

    @Builder.Default
    @JsonProperty("isActive")
    private boolean isActive = true;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "org_id")
    private UUID orgId;
    
    @JsonProperty("ingredientId")
    public UUID getIngredientId() {
        return ingredient != null ? ingredient.getId() : null;
    }

    @JsonProperty("ingredientName")
    public String getIngredientName() {
        return ingredient != null ? ingredient.getName() : null;
    }
}
