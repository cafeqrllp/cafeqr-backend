package com.restaurant.pos.pos.sale.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Lightweight Spring Data interface-based projection for POS Product Catalog.
 * Contains only the columns required to render product cards and calculate sales,
 * avoiding entity hydration and unnecessary column transfers.
 */
public interface PosProductSummaryView {
    UUID getId();
    String getName();
    String getDescription();
    BigDecimal getPrice();
    BigDecimal getCostPrice();
    BigDecimal getMrp();
    Boolean getIsAvailable();
    String getImageUrl();
    UUID getCategoryId();
    String getCategoryName();
    String getProductCode();
    String getBarcode();
    String getProductType();
    BigDecimal getTaxRate();
    String getTaxCode();
    Boolean getIsActive();
    Boolean getIsPackagedGood();
    Boolean getIsIngredient();
    Boolean getIsVariablePrice();
    Boolean getIsVariant();
}
