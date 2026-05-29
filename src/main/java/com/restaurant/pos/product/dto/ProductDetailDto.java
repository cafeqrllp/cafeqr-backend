package com.restaurant.pos.product.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDetailDto {
    private UUID id;
    private String name;
    private String description;
    private BigDecimal price;

    @JsonProperty("isAvailable")
    private boolean isAvailable;

    private String imageUrl;
    private String productType;

    @JsonProperty("isVariant")
    private boolean isVariant;

    @JsonProperty("isPackagedGood")
    private boolean isPackagedGood;

    @JsonProperty("isIngredient")
    private boolean isIngredient;

    private String productCode;
    private BigDecimal taxRate;
    private String taxCode;
    private BigDecimal mrp;
    private BigDecimal costPrice;
    private String barcode;
    private Integer minStockLevel;
    private String kdsStation;

    @JsonProperty("isActive")
    private boolean isActive;

    private CategorySummary category;
    private UomSummary uom;

    @Builder.Default
    private List<VariantMappingDto> variantMappings = new ArrayList<>();

    @Builder.Default
    private List<VariantPricingDto> variantPricings = new ArrayList<>();

    @Builder.Default
    private List<UpsellDto> upsells = new ArrayList<>();

    @Builder.Default
    private List<RecipeDto> recipeLines = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecipeDto {
        private UUID id;
        private UUID ingredientId;
        private String ingredientName;
        private BigDecimal quantity;
        private String uomName;
        @JsonProperty("isActive")
        private boolean isActive;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategorySummary {
        private UUID id;
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UomSummary {
        private UUID id;
        private String name;
        private String shortName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariantMappingDto {
        private UUID id;

        @JsonProperty("isRequired")
        private boolean isRequired;

        private VariantGroupDto variantGroup;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariantPricingDto {
        private UUID id;
        private BigDecimal overridePrice;

        @JsonProperty("isAvailable")
        private boolean isAvailable;

        private VariantOptionDto variantOption;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpsellDto {
        private UUID id;

        @JsonProperty("isActive")
        private boolean isActive;

        private ProductSummary upsellProduct;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductSummary {
        private UUID id;
        private String name;
        private BigDecimal price;
    }
}
