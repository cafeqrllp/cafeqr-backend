package com.restaurant.pos.pos.sale.query;

import com.restaurant.pos.pos.sale.dto.ProductCategoryBean;
import com.restaurant.pos.product.domain.CategorySortMode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Orders POS category tabs. Pure logic: never drops items, never mutates the input.
 * Every mode falls back to A-Z by name; categories without data sort after those with data.
 */
public final class CategorySorter {

    private static final Comparator<ProductCategoryBean> BY_NAME =
            Comparator.comparing(ProductCategoryBean::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

    private CategorySorter() {
    }

    public static List<ProductCategoryBean> sort(List<ProductCategoryBean> beans, CategorySortMode mode) {
        if (beans == null || beans.isEmpty()) {
            return Collections.emptyList();
        }
        List<ProductCategoryBean> result = new ArrayList<>(beans);
        result.sort(comparatorFor(mode == null ? CategorySortMode.NAME_ASC : mode));
        return result;
    }

    private static Comparator<ProductCategoryBean> comparatorFor(CategorySortMode mode) {
        return switch (mode) {
            case SEQUENCE -> Comparator.comparing(ProductCategoryBean::getSequenceNo, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                    .thenComparing(BY_NAME);
            case NAME_ASC -> BY_NAME;
            case NAME_DESC -> BY_NAME.reversed();
            case MOST_SOLD -> Comparator.comparing(ProductCategoryBean::getQtySold, Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
                    .thenComparing(BY_NAME);
            case LEAST_SOLD -> Comparator.comparing(ProductCategoryBean::getQtySold, Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder()))
                    .thenComparing(BY_NAME);
            case HIGHEST_REVENUE -> Comparator.comparing(ProductCategoryBean::getSalesAmount, Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
                    .thenComparing(BY_NAME);
            case RECENTLY_SOLD -> Comparator.comparing(ProductCategoryBean::getLastSoldAt, Comparator.nullsLast(Comparator.<java.time.LocalDateTime>reverseOrder()))
                    .thenComparing(BY_NAME);
            case MOST_PRODUCTS -> Comparator.<ProductCategoryBean>comparingInt(b -> b.getProductCount() == null ? 0 : b.getProductCount())
                    .reversed().thenComparing(BY_NAME);
        };
    }
}
