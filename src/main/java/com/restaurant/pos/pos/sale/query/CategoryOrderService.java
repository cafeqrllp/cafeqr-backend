package com.restaurant.pos.pos.sale.query;

import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.pos.sale.dto.ProductCategoryBean;
import com.restaurant.pos.product.domain.CategorySortMode;
import com.restaurant.pos.product.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Orders POS category beans per the branch's category sort mode, loading sales
 * or product-count data only when the mode needs it.
 */
@Service
public class CategoryOrderService {

    static final Duration SALES_WINDOW = Duration.ofDays(30);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final SystemConfigurationService configurationService;

    public CategoryOrderService(OrderRepository orderRepository,
                                ProductRepository productRepository,
                                SystemConfigurationService configurationService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.configurationService = configurationService;
    }

    public List<ProductCategoryBean> order(List<ProductCategoryBean> beans, UUID clientId, UUID orgId) {
        if (beans == null || beans.isEmpty()) {
            return List.of();
        }
        ConfigurationDto config = configurationService.getConfigurationForClientAndBranch(clientId, orgId);
        CategorySortMode mode = CategorySortMode.fromValue(config == null ? null : config.getCategorySortMode());

        switch (mode) {
            case MOST_SOLD, LEAST_SOLD, HIGHEST_REVENUE, RECENTLY_SOLD -> mergeSalesStats(beans, clientId, orgId);
            case MOST_PRODUCTS -> mergeProductCounts(beans, clientId, orgId);
            default -> { /* SEQUENCE and name modes need no extra data */ }
        }
        return CategorySorter.sort(beans, mode);
    }

    private void mergeSalesStats(List<ProductCategoryBean> beans, UUID clientId, UUID orgId) {
        List<Object[]> rows = orderRepository.findCategorySalesStats(clientId, orgId, Instant.now().minus(SALES_WINDOW));
        if (rows == null) {
            return;
        }
        Map<String, Object[]> byName = new HashMap<>();
        for (Object[] r : rows) {
            if (r[0] != null) {
                byName.put(r[0].toString().trim().toLowerCase(Locale.ROOT), r);
            }
        }
        for (ProductCategoryBean b : beans) {
            if (b.getName() == null) continue;
            Object[] r = byName.get(b.getName().trim().toLowerCase(Locale.ROOT));
            if (r == null) continue;
            b.setQtySold((BigDecimal) r[1]);
            b.setSalesAmount((BigDecimal) r[2]);
            b.setLastSoldAt(r[3] instanceof Instant i ? LocalDateTime.ofInstant(i, ZoneId.systemDefault()) : null);
        }
    }

    private void mergeProductCounts(List<ProductCategoryBean> beans, UUID clientId, UUID orgId) {
        List<Object[]> rows = productRepository.countActiveSellableByCategory(clientId, orgId);
        if (rows == null) {
            return;
        }
        Map<String, Integer> byId = new HashMap<>();
        for (Object[] r : rows) {
            if (r[0] != null && r[1] != null) {
                byId.put(r[0].toString(), ((Number) r[1]).intValue());
            }
        }
        for (ProductCategoryBean b : beans) {
            if (b.getId() != null) {
                b.setProductCount(byId.get(b.getId()));
            }
        }
    }
}
