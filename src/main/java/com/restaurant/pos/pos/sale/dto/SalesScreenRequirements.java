package com.restaurant.pos.pos.sale.dto;

import lombok.Builder;

/**
 * Requirement and feature-decision layer for the POS Sales Screen.
 * Derived purely from POS configuration to dictate which components,
 * datasets, and conditional queries are required.
 */
@Builder
public record SalesScreenRequirements(
        boolean isStandardMode,
        boolean isTableEnabled,
        boolean isCustomerEnabled,
        boolean isLoyaltyEnabled,
        boolean isWaiterEnabled,
        boolean isDiscountEnabled,
        boolean isBarcodeScannerEnabled
) {
    public static SalesScreenRequirements from(SalesScreenConfiguration config) {
        if (config == null) {
            return SalesScreenRequirements.builder()
                    .isStandardMode(true)
                    .isTableEnabled(false)
                    .isCustomerEnabled(false)
                    .isLoyaltyEnabled(false)
                    .isWaiterEnabled(false)
                    .isDiscountEnabled(false)
                    .isBarcodeScannerEnabled(false)
                    .build();
        }

        return SalesScreenRequirements.builder()
                .isStandardMode("STANDARD".equalsIgnoreCase(config.getSalesType()))
                .isTableEnabled(config.isTableEnabled())
                .isCustomerEnabled(config.isCustomerEnabled())
                .isLoyaltyEnabled(config.isLoyaltyEnabled())
                .isWaiterEnabled(config.isWaiterEnabled())
                .isDiscountEnabled(config.isDiscountEnabled())
                .isBarcodeScannerEnabled(config.isBarcodeScannerEnabled())
                .build();
    }
}
