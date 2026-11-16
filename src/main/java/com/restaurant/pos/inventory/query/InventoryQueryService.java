package com.restaurant.pos.inventory.query;

import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.inventory.domain.StockAdjustment;
import com.restaurant.pos.inventory.domain.StockSnapshot;
import com.restaurant.pos.inventory.domain.StockTransfer;
import com.restaurant.pos.inventory.repository.StockAdjustmentRepository;
import com.restaurant.pos.inventory.repository.StockSnapshotRepository;
import com.restaurant.pos.inventory.repository.StockTransferRepository;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.product.repository.VariantOptionRepository;
import com.restaurant.pos.product.domain.VariantOption;
import com.restaurant.pos.product.repository.VariantPricingRepository;
import com.restaurant.pos.product.domain.VariantPricing;
import com.restaurant.pos.warehouse.domain.Warehouse;
import com.restaurant.pos.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.restaurant.pos.auth.repository.UserRepository;
import com.restaurant.pos.inventory.domain.StockAdjustmentLine;
import com.restaurant.pos.inventory.domain.StockTransferLine;
import com.restaurant.pos.product.domain.Product;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryQueryService {

    private final StockSnapshotRepository stockSnapshotRepository;
    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final StockTransferRepository stockTransferRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final VariantOptionRepository variantOptionRepository;
    private final VariantPricingRepository variantPricingRepository;
    private final UserRepository userRepository;
    private final BranchContextService branchContext;
    private final com.restaurant.pos.costing.query.CostingQueryService costingQueryService;

    private String resolveUserName(String userIdStr) {
        if (userIdStr == null || userIdStr.isBlank() || "SYSTEM".equalsIgnoreCase(userIdStr)) {
            return "System";
        }
        try {
            UUID uid = UUID.fromString(userIdStr);
            return userRepository.findById(uid)
                    .map(u -> {
                        String fn = u.getFirstName() != null ? u.getFirstName() : "";
                        String ln = u.getLastName() != null ? u.getLastName() : "";
                        String full = (fn + " " + ln).trim();
                        return full.isEmpty() ? (u.getEmail() != null ? u.getEmail() : userIdStr) : full;
                    })
                    .orElse(userIdStr);
        } catch (Exception e) {
            return userIdStr;
        }
    }

    private List<StockSnapshot> consolidateByProductAndVariant(List<StockSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, StockSnapshot> map = new HashMap<>();
        for (StockSnapshot snap : snapshots) {
            if (snap.getProductId() == null) continue;
            String key = snap.getVariantId() != null 
                    ? snap.getProductId() + "_" + snap.getVariantId() 
                    : snap.getProductId().toString();
            if (map.containsKey(key)) {
                StockSnapshot existing = map.get(key);
                existing.setCurrentQuantity(existing.getCurrentQuantity().add(snap.getCurrentQuantity()));
            } else {
                StockSnapshot copy = StockSnapshot.builder()
                        .id(snap.getId())
                        .clientId(snap.getClientId())
                        .orgId(snap.getOrgId())
                        .warehouseId(snap.getWarehouseId())
                        .productId(snap.getProductId())
                        .variantId(snap.getVariantId())
                        .currentQuantity(snap.getCurrentQuantity())
                        .lastUpdated(snap.getLastUpdated())
                        .build();
                map.put(key, copy);
            }
        }
        return new ArrayList<>(map.values());
    }

    public List<StockSnapshot> getStockOverview(UUID warehouseId) {
        List<StockSnapshot> rawSnapshots = stockSnapshotRepository.findByWarehouseId(warehouseId);
        List<StockSnapshot> snapshots = consolidateByProductAndVariant(rawSnapshots);
        Warehouse warehouse = warehouseRepository.findById(warehouseId).orElse(null);
        if (warehouse == null) {
            return enrichWithVariantNames(snapshots);
        }
        return enrichWithVariantNames(appendRecipeProductsStock(snapshots, warehouse.getClientId(), warehouse.getOrgId(), warehouseId, false));
    }

    public List<StockSnapshot> getConsolidatedStockOverview(UUID orgId, UUID warehouseId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID effectiveOrgId = orgId != null ? orgId : TenantContext.getCurrentOrg();
        if (effectiveOrgId == null) {
            effectiveOrgId = branchContext.getReadOrgId(null);
        }
        if (effectiveOrgId == null) {
            return java.util.Collections.emptyList();
        }

        if (warehouseId != null) {
            List<StockSnapshot> raw = consolidateByProductAndVariant(stockSnapshotRepository.findByClientIdAndWarehouseId(clientId, warehouseId));
            return enrichWithVariantNames(appendRecipeProductsStock(raw, clientId, effectiveOrgId, warehouseId, false));
        }
        
        List<StockSnapshot> rawSnapshots = stockSnapshotRepository.findByClientIdAndOrgId(clientId, effectiveOrgId);
        
        List<Warehouse> activeWhs = warehouseRepository.findByClientIdAndOrgIdOrGlobalOrderByCreatedAtDesc(clientId, effectiveOrgId);
        
        java.util.Set<UUID> validWhIds = activeWhs.stream().map(Warehouse::getId).collect(Collectors.toSet());

        List<StockSnapshot> validSnapshots = rawSnapshots.stream()
                .filter(s -> s.getWarehouseId() != null && validWhIds.contains(s.getWarehouseId()))
                .collect(Collectors.toList());
        
        List<StockSnapshot> consolidatedSnapshots = consolidateByProductAndVariant(validSnapshots);
        return enrichWithVariantNames(appendRecipeProductsStock(consolidatedSnapshots, clientId, effectiveOrgId, null, true));
    }

    public Page<StockAdjustment> getAdjustments(UUID orgId, Pageable pageable) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID effectiveOrgId = branchContext.getReadOrgId(orgId);
        Page<StockAdjustment> page;
        if (effectiveOrgId != null) {
            page = stockAdjustmentRepository.findByClientIdAndOrgIdOrderByAdjustmentDateDesc(clientId, effectiveOrgId, pageable);
        } else {
            page = stockAdjustmentRepository.findByClientIdOrderByAdjustmentDateDesc(clientId, pageable);
        }
        page.getContent().forEach(adj -> {
            adj.setCreatedByName(resolveUserName(adj.getCreatedBy()));
            adj.setUpdatedByName(resolveUserName(adj.getUpdatedBy()));
            if (adj.getLines() != null) {
                for (StockAdjustmentLine line : adj.getLines()) {
                    if (line.getProductId() != null) {
                        productRepository.findById(line.getProductId()).ifPresent(p -> {
                            line.setProductName(p.getName());
                            line.setSku(p.getProductCode());
                            if (p.getCategory() != null) {
                                line.setCategoryName(p.getCategory().getName());
                            }
                        });
                    }
                }
            }
        });
        return page;
    }


    public Page<StockTransfer> getTransfers(UUID orgId, Pageable pageable) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID effectiveOrgId = branchContext.getReadOrgId(orgId);
        Page<StockTransfer> page;
        if (effectiveOrgId != null) {
            page = stockTransferRepository.findByClientIdAndOrgIdOrDestOrgId(clientId, effectiveOrgId, pageable);
        } else {
            page = stockTransferRepository.findByClientIdOrderByTransferDateDesc(clientId, pageable);
        }
        page.getContent().forEach(t -> {
            t.setCreatedByName(resolveUserName(t.getCreatedBy()));
            t.setUpdatedByName(resolveUserName(t.getUpdatedBy()));
        });
        return page;
    }

    public StockTransfer getTransferById(UUID id) {
        StockTransfer transfer = stockTransferRepository.findById(id)
                .orElseThrow(() -> new com.restaurant.pos.common.exception.ResourceNotFoundException("Stock transfer not found with ID: " + id));

        transfer.setCreatedByName(resolveUserName(transfer.getCreatedBy()));
        transfer.setUpdatedByName(resolveUserName(transfer.getUpdatedBy()));

        if (transfer.getLines() != null) {
            for (StockTransferLine line : transfer.getLines()) {
                if (line.getProductId() != null) {
                    productRepository.findById(line.getProductId()).ifPresent(p -> {
                        line.setProductName(p.getName());
                        line.setSku(p.getProductCode());
                        if (p.getCategory() != null) {
                            line.setCategoryName(p.getCategory().getName());
                        }
                    });
                }
            }
        }
        return transfer;
    }

    public StockAdjustment getAdjustmentById(UUID id) {
        StockAdjustment adjustment = stockAdjustmentRepository.findById(id)
                .orElseThrow(() -> new com.restaurant.pos.common.exception.ResourceNotFoundException("Stock adjustment not found with ID: " + id));

        adjustment.setCreatedByName(resolveUserName(adjustment.getCreatedBy()));
        adjustment.setUpdatedByName(resolveUserName(adjustment.getUpdatedBy()));

        if (adjustment.getLines() != null) {
            for (StockAdjustmentLine line : adjustment.getLines()) {
                if (line.getProductId() != null) {
                    productRepository.findById(line.getProductId()).ifPresent(p -> {
                        line.setProductName(p.getName());
                        line.setSku(p.getProductCode());
                        if (p.getCategory() != null) {
                            line.setCategoryName(p.getCategory().getName());
                        }
                    });
                }
            }
        }

        return adjustment;
    }

    private List<StockSnapshot> enrichWithVariantNames(List<StockSnapshot> snapshots) {
        enrichWithCurrentCost(snapshots);
        List<UUID> variantIds = snapshots.stream()
                .filter(s -> s.getVariantId() != null)
                .map(StockSnapshot::getVariantId)
                .distinct()
                .collect(Collectors.toList());
        if (variantIds.isEmpty()) return snapshots;

        // Build variantId -> name map
        Map<UUID, String> nameMap = new HashMap<>();
        variantOptionRepository.findAllById(variantIds)
                .forEach(vo -> nameMap.put(vo.getId(), vo.getName()));

        // Build variantId -> costPrice map from variant_pricing
        List<UUID> productIds = snapshots.stream()
                .filter(s -> s.getVariantId() != null && s.getProductId() != null)
                .map(StockSnapshot::getProductId)
                .distinct()
                .collect(Collectors.toList());
        Map<UUID, java.math.BigDecimal> costMap = new HashMap<>();
        for (UUID pid : productIds) {
            variantPricingRepository.findByProductId(pid).forEach(vp -> {
                if (vp.getVariantOption() != null) {
                    java.math.BigDecimal cost = vp.getCostPrice() != null ? vp.getCostPrice()
                            : (vp.getOverridePrice() != null ? vp.getOverridePrice() : null);
                    if (cost != null) {
                        costMap.put(vp.getVariantOption().getId(), cost);
                    }
                }
            });
        }

        snapshots.forEach(s -> {
            if (s.getVariantId() != null) {
                s.setVariantOptionName(nameMap.getOrDefault(s.getVariantId(), null));
                s.setVariantCostPrice(costMap.getOrDefault(s.getVariantId(), null));
            }
        });
        return snapshots;
    }

    /** Fills the transient currentCost from product_costs (org-level cost) for stock valuation. */
    private void enrichWithCurrentCost(List<StockSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        Map<UUID, List<StockSnapshot>> byClient = snapshots.stream()
                .filter(s -> s.getClientId() != null && s.getProductId() != null && s.getOrgId() != null)
                .collect(Collectors.groupingBy(StockSnapshot::getClientId));
        byClient.forEach((clientId, list) -> {
            List<UUID> productIds = list.stream().map(StockSnapshot::getProductId).distinct().collect(Collectors.toList());
            Map<String, java.math.BigDecimal> costs = costingQueryService.currentCostsForClient(clientId, productIds);
            list.forEach(s -> s.setCurrentCost(costs.get(com.restaurant.pos.costing.query.CostingQueryService
                    .key(s.getOrgId(), s.getProductId(), s.getVariantId()))));
        });
    }

    private List<StockSnapshot> appendRecipeProductsStock(List<StockSnapshot> snapshots, UUID clientId, UUID orgId, UUID warehouseId, boolean isConsolidated) {
        List<StockSnapshot> resultList = new ArrayList<>(snapshots);
        
        Map<UUID, BigDecimal> ingredientStockMap = new HashMap<>();
        for (StockSnapshot snap : resultList) {
            UUID prodId = snap.getProductId();
            BigDecimal qty = snap.getCurrentQuantity();
            if (prodId != null && qty != null) {
                ingredientStockMap.merge(prodId, qty, BigDecimal::add);
            }
        }
        
        List<com.restaurant.pos.product.domain.Product> products = productRepository.findByClientIdAndOrgIdOrGlobalAndIsActiveTrue(clientId, orgId);
        
        for (com.restaurant.pos.product.domain.Product p : products) {
            if (p.getRecipeLines() != null && !p.getRecipeLines().isEmpty()) {
                List<com.restaurant.pos.product.domain.ProductRecipe> activeLines = p.getRecipeLines().stream()
                        .filter(com.restaurant.pos.product.domain.ProductRecipe::isActive)
                        .collect(Collectors.toList());
                
                if (activeLines.isEmpty()) {
                    continue;
                }
                
                BigDecimal minAvailable = null;
                for (com.restaurant.pos.product.domain.ProductRecipe line : activeLines) {
                    if (line.getIngredient() == null || line.getQuantity() == null || line.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }
                    
                    UUID ingId = line.getIngredient().getId();
                    BigDecimal ingStock = ingredientStockMap.getOrDefault(ingId, BigDecimal.ZERO);
                    
                    BigDecimal possible = ingStock.divide(line.getQuantity(), 3, java.math.RoundingMode.DOWN);
                    if (minAvailable == null || possible.compareTo(minAvailable) < 0) {
                        minAvailable = possible;
                    }
                }
                
                if (minAvailable == null || minAvailable.compareTo(BigDecimal.ZERO) < 0) {
                    minAvailable = BigDecimal.ZERO;
                }
                
                boolean found = false;
                for (StockSnapshot existing : resultList) {
                    if (existing.getProductId().equals(p.getId()) && existing.getVariantId() == null) {
                        found = true;
                        break;
                    }
                }
                
                if (!found) {
                    StockSnapshot virtualSnap = StockSnapshot.builder()
                            .id(null)
                            .clientId(clientId)
                            .orgId(orgId)
                            .warehouseId(isConsolidated ? null : warehouseId)
                            .productId(p.getId())
                            .variantId(null)
                            .currentQuantity(minAvailable)
                            .lastUpdated(LocalDateTime.now())
                            .build();
                    resultList.add(virtualSnap);
                }
            }
        }
        
        return resultList;
    }
}
