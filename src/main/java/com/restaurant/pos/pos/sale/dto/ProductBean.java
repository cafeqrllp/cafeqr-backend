package com.restaurant.pos.pos.sale.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Dedicated product projection bean for the POS Sales Screen.
 * Contains only the fields required for card rendering, pricing, and cart operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductBean {
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
    private Boolean isPackagedGood;
    private Boolean isVariablePrice;
    private Boolean isVariant;
    private Boolean hasVariants;
    private Integer variantCount;
    private Boolean hasUpsells;
    private Integer upsellCount;
}
