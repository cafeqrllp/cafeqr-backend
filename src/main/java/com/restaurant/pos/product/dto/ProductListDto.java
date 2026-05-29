package com.restaurant.pos.product.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import com.restaurant.pos.product.domain.ProductVariantMapping;
import com.restaurant.pos.product.domain.VariantPricing;
import com.restaurant.pos.product.domain.ProductUpsell;
import com.restaurant.pos.purchasing.domain.PricelistProduct;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductListDto {
    private UUID id;
    private String name;
    private String description;
    private BigDecimal price;
    @JsonProperty("isAvailable")
    private boolean isAvailable;
    private String imageUrl;
    private UUID categoryId;
    private String categoryName;

    private String uomName;
    private UUID uomId;
    private String productCode;
    private String productType;
    private BigDecimal taxRate;
    private String taxCode;
    @JsonProperty("isActive")
    private boolean isActive;
    @JsonProperty("isPackagedGood")
    private boolean isPackagedGood;
    @JsonProperty("isIngredient")
    private boolean isIngredient;
    private boolean hasVariants;
    private int variantCount;
    private boolean hasUpsells;
    private int upsellCount;
    private boolean hasIngredients;
    private UUID defaultPricelistId;
    private String defaultPricelistName;
}
