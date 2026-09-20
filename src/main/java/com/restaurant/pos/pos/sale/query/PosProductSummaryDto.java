package com.restaurant.pos.pos.sale.query;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Concrete DTO implementing {@link PosProductSummaryView} for Redis caching and serialization.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PosProductSummaryDto implements PosProductSummaryView {
    private UUID id;
    private String name;
    private String description;
    private BigDecimal price;
    private BigDecimal costPrice;
    private BigDecimal mrp;
    private Boolean isAvailable;
    private String imageUrl;
    private UUID categoryId;
    private String categoryName;
    private String productCode;
    private String barcode;
    private String productType;
    private BigDecimal taxRate;
    private String taxCode;
    private Boolean isActive;
    private Boolean isPackagedGood;
    private Boolean isIngredient;
    private Boolean isVariablePrice;
    private Boolean isVariant;
    private Boolean hasVariants;
    private Integer variantCount;
    private Boolean hasUpsells;
    private Integer upsellCount;

    public static PosProductSummaryDto from(PosProductSummaryView v) {
        return from(v, true);
    }

    public static PosProductSummaryDto from(PosProductSummaryView v, boolean includeImages) {
        if (v == null) return null;
        return PosProductSummaryDto.builder()
                .id(v.getId())
                .name(v.getName())
                .description(v.getDescription())
                .price(v.getPrice())
                .costPrice(v.getCostPrice())
                .mrp(v.getMrp())
                .isAvailable(v.getIsAvailable())
                .imageUrl(includeImages ? v.getImageUrl() : null)
                .categoryId(v.getCategoryId())
                .categoryName(v.getCategoryName())
                .productCode(v.getProductCode())
                .barcode(v.getBarcode())
                .productType(v.getProductType())
                .taxRate(v.getTaxRate())
                .taxCode(v.getTaxCode())
                .isActive(v.getIsActive())
                .isPackagedGood(v.getIsPackagedGood())
                .isIngredient(v.getIsIngredient())
                .isVariablePrice(v.getIsVariablePrice())
                .isVariant(v.getIsVariant())
                .hasVariants(v.getHasVariants())
                .variantCount(v.getVariantCount())
                .hasUpsells(v.getHasUpsells())
                .upsellCount(v.getUpsellCount())
                .build();
    }
}
