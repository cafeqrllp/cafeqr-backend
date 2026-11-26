package com.restaurant.pos.pos.sale.query;

import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.pos.sale.dto.ProductCategoryBean;
import com.restaurant.pos.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoryOrderServiceTest {

    private final UUID clientId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private OrderRepository orderRepository;
    private ProductRepository productRepository;
    private SystemConfigurationService configurationService;
    private CategoryOrderService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        productRepository = mock(ProductRepository.class);
        configurationService = mock(SystemConfigurationService.class);
        service = new CategoryOrderService(orderRepository, productRepository, configurationService);
    }

    private void mode(String m) {
        when(configurationService.getConfigurationForClientAndBranch(clientId, orgId))
                .thenReturn(ConfigurationDto.builder().categorySortMode(m).build());
    }

    private List<ProductCategoryBean> beans() {
        return new ArrayList<>(List.of(
                ProductCategoryBean.builder().id(UUID.randomUUID().toString()).name("Coffee").build(),
                ProductCategoryBean.builder().id(UUID.randomUUID().toString()).name("Juice").build(),
                ProductCategoryBean.builder().id(UUID.randomUUID().toString()).name("Bakery").build()));
    }

    @Test
    void mostSold_mergesStatsCaseInsensitivelyAndKeepsNoSalesCategories() {
        mode("MOST_SOLD");
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"juice", new BigDecimal("40"), new BigDecimal("400"), Instant.now()});
        rows.add(new Object[]{"COFFEE", new BigDecimal("5"), new BigDecimal("50"), Instant.now()});
        when(orderRepository.findCategorySalesStats(eq(clientId), eq(orgId), any(Instant.class))).thenReturn(rows);

        List<ProductCategoryBean> out = service.order(beans(), clientId, orgId);

        assertThat(out).extracting(ProductCategoryBean::getName).containsExactly("Juice", "Coffee", "Bakery");
        assertThat(out.get(0).getQtySold()).isEqualByComparingTo("40");
    }

    @Test
    void salesStatsQueryUsesThirtyDayWindow() {
        mode("MOST_SOLD");
        when(orderRepository.findCategorySalesStats(any(), any(), any())).thenReturn(List.of());
        Instant before = Instant.now();

        service.order(beans(), clientId, orgId);

        org.mockito.ArgumentCaptor<Instant> since = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(orderRepository).findCategorySalesStats(eq(clientId), eq(orgId), since.capture());
        long days = java.time.Duration.between(since.getValue(), before).toDays();
        assertThat(days).isBetween(29L, 30L);
    }

    @Test
    void nameAsc_doesNotRunStatsQueries() {
        mode("NAME_ASC");
        List<ProductCategoryBean> out = service.order(beans(), clientId, orgId);
        assertThat(out).extracting(ProductCategoryBean::getName).containsExactly("Bakery", "Coffee", "Juice");
        verify(orderRepository, never()).findCategorySalesStats(any(), any(), any());
        verify(productRepository, never()).countActiveSellableByCategory(any(), any());
    }

    @Test
    void unknownModeFallsBackToNameAsc() {
        mode("GARBAGE");
        assertThat(service.order(beans(), clientId, orgId)).extracting(ProductCategoryBean::getName)
                .containsExactly("Bakery", "Coffee", "Juice");
    }

    @Test
    void mostProducts_usesProductCountsByCategoryId() {
        mode("MOST_PRODUCTS");
        List<ProductCategoryBean> in = beans();
        List<Object[]> counts = new ArrayList<>();
        counts.add(new Object[]{UUID.fromString(in.get(2).getId()), 9L}); // Bakery
        counts.add(new Object[]{UUID.fromString(in.get(0).getId()), 2L}); // Coffee
        when(productRepository.countActiveSellableByCategory(eq(clientId), eq(orgId))).thenReturn(counts);

        assertThat(service.order(in, clientId, orgId)).extracting(ProductCategoryBean::getName)
                .containsExactly("Bakery", "Coffee", "Juice");
    }
}
