package com.restaurant.pos.product.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CategorySortModeTest {

    @Test
    void fromValue_isCaseInsensitiveAndTrimmed() {
        assertEquals(CategorySortMode.MOST_SOLD, CategorySortMode.fromValue(" most_sold "));
        assertEquals(CategorySortMode.SEQUENCE, CategorySortMode.fromValue("SEQUENCE"));
    }

    @Test
    void fromValue_defaultsToNameAscForNullBlankOrUnknown() {
        assertEquals(CategorySortMode.NAME_ASC, CategorySortMode.fromValue(null));
        assertEquals(CategorySortMode.NAME_ASC, CategorySortMode.fromValue("  "));
        assertEquals(CategorySortMode.NAME_ASC, CategorySortMode.fromValue("BOGUS"));
    }
}
