package com.restaurant.pos.qrmenu.listener;

import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.warehouse.domain.Warehouse;
import com.restaurant.pos.inventory.service.InventoryService;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.domain.ProductRecipe;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.qrmenu.event.QrOrderPlacedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Event listener that handles stock deduction for QR orders when the inventory module is enabled.
 * Decoupled from the command service via QrOrderPlacedEvent (matching PurchaseOrderInventoryListener pattern).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QrOrderInventoryListener {

    private final InventoryService inventoryService;
    private final SystemConfigurationService systemConfigurationService;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    @EventListener
    public void onQrOrderPlaced(QrOrderPlacedEvent event) {
        Order order = event.getOrder();
        List<OrderLine> lines = event.getNewLines();
        UUID clientId = event.getClientId();
        UUID orgId = event.getOrgId();

        if (order == null || lines == null || lines.isEmpty()) {
            return;
        }

        try {
            ConfigurationDto config = systemConfigurationService.getConfigurationForClientAndBranch(clientId, orgId);
            if (config == null || !config.isInventoryEnabled()) {
                log.info("Inventory module is disabled for client={} org={} — skipping stock deduction for QR order {}",
                        clientId, orgId, order.getId());
                return;
            }

            UUID warehouseId = inventoryService.findDefaultWarehouse(clientId, orgId)
                    .map(Warehouse::getId)
                    .orElse(null);

            if (warehouseId == null) {
                log.warn("Skipping stock deduction for QR order {} — no warehouse found for org {}",
                        order.getId(), orgId);
                return;
            }

            UUID prevTenant = TenantContext.getCurrentTenant();
            UUID prevOrg = TenantContext.getCurrentOrg();
            try {
                TenantContext.setCurrentTenant(clientId);
                TenantContext.setCurrentOrg(orgId);
                boolean deductedAny = false;
                for (OrderLine line : lines) {
                    if (line.getProductId() == null) continue;
                    BigDecimal soldQty = line.getQuantity() != null ? line.getQuantity() : BigDecimal.ONE;
                    if (soldQty.signum() <= 0) continue;

                    Product product = productRepository.findById(line.getProductId()).orElse(null);
                    List<ProductRecipe> recipes = product != null ? getActiveRecipes(product) : Collections.emptyList();

                    if (recipes.isEmpty()) {
                        // Direct product stock deduction
                        deductStock(warehouseId, line.getProductId(), line.getVariantId(), soldQty, order, orgId);
                        deductedAny = true;
                    } else {
                        // Recipe-based BOM explosion
                        UUID variantId = line.getVariantId();

                        List<ProductRecipe> variantRecipes = recipes.stream()
                                .filter(r -> r.getVariantOption() != null
                                        && variantId != null
                                        && variantId.equals(r.getVariantOption().getId()))
                                .toList();

                        Set<UUID> overriddenIngredients = variantRecipes.stream()
                                .filter(r -> r.getIngredient() != null)
                                .map(r -> r.getIngredient().getId())
                                .collect(Collectors.toSet());

                        for (ProductRecipe recipe : recipes) {
                            if (!isValidRecipe(recipe)) continue;

                            boolean isVariantRecipe = recipe.getVariantOption() != null;
                            boolean isSelectedVariant = isVariantRecipe
                                    && Objects.equals(variantId, recipe.getVariantOption().getId());

                            if (isVariantRecipe && !isSelectedVariant) continue;

                            UUID ingredientId = recipe.getIngredient().getId();
                            if (!isVariantRecipe && overriddenIngredients.contains(ingredientId)) continue;

                            deductStock(warehouseId, ingredientId, null,
                                    soldQty.multiply(recipe.getQuantity()), order, orgId);
                            deductedAny = true;
                        }
                    }
                }
                if (deductedAny) {
                    order.setIsStockDeducted(true);
                    order.setWarehouseId(warehouseId);
                    orderRepository.save(order);
                }
                log.info("EventListener: Stock deducted (isStockDeducted={}) for QR order {} ({} lines) in warehouse {}",
                        order.getIsStockDeducted(), order.getId(), lines.size(), warehouseId);
            } finally {
                TenantContext.setCurrentTenant(prevTenant);
                TenantContext.setCurrentOrg(prevOrg);
            }
        } catch (Exception ex) {
            log.warn("EventListener: Failed to deduct stock for QR order {} — order committed, stock skipped: {}",
                    order.getId(), ex.getMessage());
        }
    }

    private List<ProductRecipe> getActiveRecipes(Product product) {
        if (product.getRecipeLines() == null) return Collections.emptyList();
        return product.getRecipeLines().stream()
                .filter(ProductRecipe::isActive)
                .collect(Collectors.toList());
    }

    private boolean isValidRecipe(ProductRecipe recipe) {
        return recipe.getIngredient() != null
                && recipe.getQuantity() != null
                && recipe.getQuantity().signum() > 0;
    }

    private void deductStock(UUID warehouseId, UUID stockItemId, UUID variantId,
                             BigDecimal quantity, Order order, UUID orgId) {
        try {
            inventoryService.updateStock(
                    warehouseId,
                    stockItemId,
                    variantId,
                    quantity.negate(),
                    "SALE_DEDUCTION",
                    order.getId(),
                    BigDecimal.ZERO,
                    orgId,
                    order.getClientId());

            log.debug("QR order inventory listener: deducted {} of stock item {} for order {}",
                    quantity, stockItemId, order.getId());
        } catch (Exception e) {
            log.error("QR order inventory listener: failed to deduct stock item {} for order {}",
                    stockItemId, order.getId(), e);
        }
    }
}
