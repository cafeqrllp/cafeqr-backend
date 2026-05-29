package com.restaurant.pos.product.domain;

import com.restaurant.pos.common.entity.AuditableEntity;
import com.restaurant.pos.purchasing.domain.PricelistProduct;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table(name = "products")
public class Product extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Builder.Default
    private UUID id = null;

    private String name;
    private String description;

    @Column(nullable = false)
    private BigDecimal price;

    @Builder.Default
    @JsonProperty("isAvailable")
    private boolean isAvailable = true;
    
    @Column(columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "org_id")
    private UUID orgId;

    // ERP Specific Fields
    private String productType;
    @JsonProperty("isVariant")
    private boolean isVariant;
    @JsonProperty("isPackagedGood")
    private boolean isPackagedGood;
    @JsonProperty("isIngredient")
    private boolean isIngredient;
    private String productCode;
    
    // Global ERP Financials & Inventory
    private BigDecimal taxRate;
    private String taxCode;
    private BigDecimal mrp;
    private BigDecimal costPrice;
    private String barcode;
    private Integer minStockLevel;
    private String kdsStation;

    @Builder.Default
    @JsonProperty("isActive")
    private boolean isActive = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uom_id")
    private Uom uom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_pricelist_id")
    private com.restaurant.pos.purchasing.domain.Pricelist defaultPricelist;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @BatchSize(size = 20)
    private List<ProductVariantMapping> variantMappings = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @BatchSize(size = 20)
    private List<VariantPricing> variantPricings = new ArrayList<>();

    @OneToMany(mappedBy = "parentProduct", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @BatchSize(size = 20)
    private List<ProductUpsell> upsells = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @BatchSize(size = 20)
    private List<PricelistProduct> pricelistProducts = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @BatchSize(size = 20)
    private List<ProductRecipe> recipeLines = new ArrayList<>();
}
