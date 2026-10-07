package com.restaurant.pos.integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreMenuExportDto {
    @JsonProperty("store_id")
    private UUID storeId;
    private String currency;
    private List<CategoryDto> categories;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryDto {
        @JsonProperty("category_id")
        private String categoryId;
        @JsonProperty("category_name")
        private String categoryName;
        @JsonProperty("display_order")
        private Integer displayOrder;
        private List<ItemDto> items;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemDto {
        @JsonProperty("item_id")
        private String itemId;
        @JsonProperty("item_name")
        private String itemName;
        private String description;
        private BigDecimal price;
        @JsonProperty("is_available")
        private boolean isAvailable;
        @JsonProperty("tax_rate_percent")
        private BigDecimal taxRatePercent;
        private List<VariantDto> variants;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariantDto {
        @JsonProperty("variant_id")
        private String variantId;
        private String name;
        private BigDecimal price;
    }
}
