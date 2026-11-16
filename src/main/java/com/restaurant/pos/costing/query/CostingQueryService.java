package com.restaurant.pos.costing.query;

import com.restaurant.pos.costing.domain.ProductCost;
import com.restaurant.pos.costing.repository.ProductCostRepository;
import com.restaurant.pos.inventory.domain.StockLedger;
import com.restaurant.pos.inventory.repository.StockLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read side of inventory costing: actual cost of goods sold per sale (from the stock ledger)
 * and current product costs (from product_costs).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CostingQueryService {

    private static final Set<String> SALE_COST_TYPES = Set.of("SALE_DEDUCTION", "SALE_VOID_RESTORE");

    private final StockLedgerRepository stockLedgerRepository;
    private final ProductCostRepository productCostRepository;

    /**
     * Net cost of goods sold per sale order, from the costed stock ledger rows written when stock was
     * deducted (recipe items count the ingredients actually consumed). Orders whose stock movements were
     * not costed (before costing existed, or no org) are absent from the map so callers can fall back.
     * An order is also left out when any item or ingredient went out at a cost of 0 (never purchased and
     * no cost price): callers then fall back to the sold item's own cost, as before costing existed,
     * instead of under-stating COGS.
     */
    public Map<UUID, BigDecimal> saleCostByOrder(Collection<UUID> orderIds) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        if (orderIds == null || orderIds.isEmpty()) {
            return result;
        }
        Set<UUID> incomplete = new HashSet<>();
        List<StockLedger> rows = stockLedgerRepository.findByReferenceIdInAndTransactionTypeIn(orderIds, SALE_COST_TYPES);
        for (StockLedger row : rows) {
            if (row.getCostAfter() == null || row.getTotalCost() == null) {
                continue; // not costed by the costing engine
            }
            if ("SALE_DEDUCTION".equals(row.getTransactionType())
                    && (row.getUnitCost() == null || row.getUnitCost().signum() == 0)) {
                incomplete.add(row.getReferenceId());
            }
            // Deductions carry a negative total cost; restores a positive one. COGS = -(sum).
            result.merge(row.getReferenceId(), row.getTotalCost().negate(), BigDecimal::add);
        }
        incomplete.forEach(result::remove);
        return result;
    }

    /** Current cost of one product/variant for an org, or null if it has never been costed. */
    public BigDecimal currentCost(UUID orgId, UUID productId, UUID variantId) {
        if (orgId == null || productId == null) {
            return null;
        }
        return productCostRepository.findCurrent(orgId, productId, variantId)
                .map(ProductCost::getCurrentCostPrice)
                .orElse(null);
    }

    /** Current costs for an org, keyed by {@link #key(UUID, UUID)}. */
    public Map<String, BigDecimal> currentCosts(UUID orgId, Collection<UUID> productIds) {
        Map<String, BigDecimal> map = new HashMap<>();
        if (orgId == null || productIds == null || productIds.isEmpty()) {
            return map;
        }
        for (ProductCost c : productCostRepository.findByOrgIdAndProductIdIn(orgId, productIds)) {
            map.put(key(c.getProductId(), c.getVariantId()), c.getCurrentCostPrice());
        }
        return map;
    }

    /**
     * Current costs across all orgs of a client (consolidated view), keyed by org and product/variant
     * via {@link #key(UUID, UUID, UUID)}.
     */
    public Map<String, BigDecimal> currentCostsForClient(UUID clientId, Collection<UUID> productIds) {
        Map<String, BigDecimal> map = new HashMap<>();
        if (clientId == null || productIds == null || productIds.isEmpty()) {
            return map;
        }
        for (ProductCost c : productCostRepository.findByClientIdAndProductIdIn(clientId, productIds)) {
            map.put(key(c.getOrgId(), c.getProductId(), c.getVariantId()), c.getCurrentCostPrice());
        }
        return map;
    }

    /** Cost records (base product and every variant) of a product for one org and client. */
    public List<com.restaurant.pos.costing.dto.ProductCostDto> productCosts(UUID clientId, UUID orgId, UUID productId) {
        if (orgId == null || productId == null) {
            return List.of();
        }
        return productCostRepository.findByOrgIdAndProductIdIn(orgId, List.of(productId)).stream()
                .filter(c -> clientId == null || c.getClientId() == null || clientId.equals(c.getClientId()))
                .map(c -> com.restaurant.pos.costing.dto.ProductCostDto.builder()
                        .productId(c.getProductId())
                        .variantId(c.getVariantId())
                        .orgId(c.getOrgId())
                        .costingMethod(c.getCostingMethod() != null ? c.getCostingMethod().name() : null)
                        .currentCost(c.getCurrentCostPrice())
                        .currentQty(c.getCurrentQty())
                        .lastPurchasePrice(c.getLastPurchasePrice())
                        .updatedAt(c.getUpdatedAt())
                        .build())
                .toList();
    }

    public static String key(UUID productId, UUID variantId) {
        return productId + ":" + (variantId != null ? variantId : "-");
    }

    public static String key(UUID orgId, UUID productId, UUID variantId) {
        return orgId + ":" + key(productId, variantId);
    }
}
