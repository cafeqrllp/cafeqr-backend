package com.restaurant.pos.product.domain;

import java.util.Locale;

/** How category tabs are ordered on the sales screens. */
public enum CategorySortMode {
    SEQUENCE,
    NAME_ASC,
    NAME_DESC,
    MOST_SOLD,
    LEAST_SOLD,
    HIGHEST_REVENUE,
    RECENTLY_SOLD,
    MOST_PRODUCTS;

    public static CategorySortMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return NAME_ASC;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return NAME_ASC;
        }
    }
}
