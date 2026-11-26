package com.restaurant.pos.pos.sale.query;

import com.restaurant.pos.pos.sale.dto.ProductCategoryBean;
import com.restaurant.pos.product.domain.CategorySortMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategorySorterTest {

    private static ProductCategoryBean bean(String name, Integer seq) {
        return ProductCategoryBean.builder().name(name).sequenceNo(seq).build();
    }

    private static List<String> names(List<ProductCategoryBean> list) {
        return list.stream().map(ProductCategoryBean::getName).toList();
    }

    private static List<ProductCategoryBean> sample() {
        return new ArrayList<>(List.of(
                bean("Snacks", 3), bean("Juice", null), bean("Coffee", 1),
                bean("Bakery", null), bean("Desserts", 3)));
    }

    @Test
    void sequence_ordersBySequenceThenNameWithNullsLast() {
        assertEquals(List.of("Coffee", "Desserts", "Snacks", "Bakery", "Juice"),
                names(CategorySorter.sort(sample(), CategorySortMode.SEQUENCE)));
    }

    @Test
    void nameAsc_and_nameDesc_ignoreSequence() {
        assertEquals(List.of("Bakery", "Coffee", "Desserts", "Juice", "Snacks"),
                names(CategorySorter.sort(sample(), CategorySortMode.NAME_ASC)));
        assertEquals(List.of("Snacks", "Juice", "Desserts", "Coffee", "Bakery"),
                names(CategorySorter.sort(sample(), CategorySortMode.NAME_DESC)));
    }

    private static List<ProductCategoryBean> withQty() {
        return new ArrayList<>(List.of(
                ProductCategoryBean.builder().name("Snacks").qtySold(new BigDecimal("50")).build(),
                ProductCategoryBean.builder().name("Juice").qtySold(new BigDecimal("5")).build(),
                ProductCategoryBean.builder().name("Coffee").qtySold(new BigDecimal("50")).build(),
                ProductCategoryBean.builder().name("Desserts").build(),
                ProductCategoryBean.builder().name("Bakery").build()));
    }

    @Test
    void mostSold_tiesByNameAndNoSalesLastAlphabetical() {
        assertEquals(List.of("Coffee", "Snacks", "Juice", "Bakery", "Desserts"),
                names(CategorySorter.sort(withQty(), CategorySortMode.MOST_SOLD)));
    }

    @Test
    void leastSold_putsNoSalesCategoriesLastNotFirst() {
        assertEquals(List.of("Juice", "Coffee", "Snacks", "Bakery", "Desserts"),
                names(CategorySorter.sort(withQty(), CategorySortMode.LEAST_SOLD)));
    }

    @Test
    void highestRevenue_ordersBySalesAmountDesc() {
        List<ProductCategoryBean> in = new ArrayList<>(List.of(
                ProductCategoryBean.builder().name("A").salesAmount(new BigDecimal("10")).build(),
                ProductCategoryBean.builder().name("B").salesAmount(new BigDecimal("99")).build(),
                ProductCategoryBean.builder().name("C").build()));
        assertEquals(List.of("B", "A", "C"), names(CategorySorter.sort(in, CategorySortMode.HIGHEST_REVENUE)));
    }

    @Test
    void recentlySold_ordersByLastSoldDescNullsLast() {
        LocalDateTime now = LocalDateTime.now();
        List<ProductCategoryBean> in = new ArrayList<>(List.of(
                ProductCategoryBean.builder().name("A").lastSoldAt(now.minusDays(3)).build(),
                ProductCategoryBean.builder().name("B").lastSoldAt(now.minusHours(1)).build(),
                ProductCategoryBean.builder().name("C").build()));
        assertEquals(List.of("B", "A", "C"), names(CategorySorter.sort(in, CategorySortMode.RECENTLY_SOLD)));
    }

    @Test
    void mostProducts_ordersByProductCountDescNullAsZero() {
        List<ProductCategoryBean> in = new ArrayList<>(List.of(
                ProductCategoryBean.builder().name("A").productCount(2).build(),
                ProductCategoryBean.builder().name("B").productCount(9).build(),
                ProductCategoryBean.builder().name("C").build()));
        assertEquals(List.of("B", "A", "C"), names(CategorySorter.sort(in, CategorySortMode.MOST_PRODUCTS)));
    }

    @Test
    void nullModeBehavesAsNameAsc_nullOrEmptyInputGivesEmpty_inputNotMutated() {
        List<ProductCategoryBean> in = sample();
        List<String> before = names(in);
        assertEquals(List.of("Bakery", "Coffee", "Desserts", "Juice", "Snacks"), names(CategorySorter.sort(in, null)));
        assertEquals(before, names(in));
        assertTrue(CategorySorter.sort(null, CategorySortMode.NAME_ASC).isEmpty());
        assertTrue(CategorySorter.sort(List.of(), CategorySortMode.MOST_SOLD).isEmpty());
    }
}
