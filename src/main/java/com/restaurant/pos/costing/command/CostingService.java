package com.restaurant.pos.costing.command;

import com.restaurant.pos.common.entity.SystemConfiguration;
import com.restaurant.pos.common.repository.SystemConfigurationRepository;
import com.restaurant.pos.costing.domain.CostResult;
import com.restaurant.pos.costing.domain.CostingMethod;
import com.restaurant.pos.costing.domain.ProductCost;
import com.restaurant.pos.costing.repository.ProductCostRepository;
import com.restaurant.pos.inventory.domain.StockLedger;
import com.restaurant.pos.inventory.repository.StockLedgerRepository;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.domain.VariantPricing;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.product.repository.VariantPricingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Maintains the running stock cost per organisation / product / variant (product_costs)
 * and decides the unit cost stamped on each stock ledger row.
 *
 * Called from {@code InventoryCommandService.updateStock} for every stock movement, inside the
 * caller's transaction, so cost and quantity always change together.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostingService {

    public static final int COST_SCALE = 6;

    static final Set<String> PURCHASE_IN_TYPES = Set.of("PURCHASE", "PURCHASE_RECEIPT");
    static final Set<String> PURCHASE_VOID_TYPES = Set.of("PURCHASE_VOID");
    static final Set<String> SALE_OUT_TYPES = Set.of("SALE_DEDUCTION");
    static final Set<String> SALE_RESTORE_TYPES = Set.of("SALE_VOID_RESTORE");
    static final Set<String> TRANSFER_OUT_TYPES = Set.of("TRANSFER_OUT");
    static final Set<String> TRANSFER_IN_TYPES = Set.of("TRANSFER_IN", "TRANSFER_VOID_REVERT");
    static final Set<String> TRANSFER_RECEIPT_TYPES = Set.of("TRANSFER_IN");
    static final Set<String> TRANSFER_VOID_TYPES = Set.of("TRANSFER_VOID_REVERT");
    static final Set<String> ADJUSTMENT_TYPES = Set.of("ADJUSTMENT");
    static final Set<String> ADJUSTMENT_VOID_TYPES = Set.of("ADJUSTMENT_VOID");

    private final ProductCostRepository productCostRepository;
    private final StockLedgerRepository stockLedgerRepository;
    private final ProductRepository productRepository;
    private final VariantPricingRepository variantPricingRepository;
    private final SystemConfigurationRepository systemConfigurationRepository;

    /**
     * Applies a stock movement to the product cost and returns the cost figures for the ledger row.
     *
     * @param inboundUnitCost cost supplied by the caller (net purchase cost for receipts, line cost for
     *                        adjustments); ignored for outbound movements, which always use the current cost
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CostResult applyMovement(UUID clientId, UUID orgId, UUID productId, UUID variantId,
                                    BigDecimal quantityChange, String transactionType, UUID referenceId,
                                    BigDecimal inboundUnitCost) {
        BigDecimal qty = quantityChange != null ? quantityChange : BigDecimal.ZERO;
        String type = transactionType != null ? transactionType.toUpperCase() : "";

        if (orgId == null || productId == null || qty.signum() == 0) {
            BigDecimal unit = scale(nz(inboundUnitCost));
            return new CostResult(unit, total(unit, qty), null, null);
        }

        CostingMethod method = resolveMethod(clientId, orgId);
        ProductCost cost = productCostRepository.findForUpdate(orgId, productId, variantId)
                .orElseGet(() -> newCostRecord(clientId, orgId, productId, variantId, method));
        cost.setCostingMethod(method);

        BigDecimal costBefore = nz(cost.getCurrentCostPrice());
        refreshKnownCost(cost, method, orgId, productId, variantId);
        BigDecimal current = nz(cost.getCurrentCostPrice());
        BigDecimal unitCost;

        if (qty.signum() > 0) {
            unitCost = resolveInboundCost(type, referenceId, productId, variantId, inboundUnitCost, current);
            applyInbound(cost, method, type, qty, unitCost);
        } else if (PURCHASE_VOID_TYPES.contains(type)) {
            unitCost = resolveReversalCost(referenceId, productId, variantId, PURCHASE_IN_TYPES, inboundUnitCost, current);
            applyPurchaseReversal(cost, method, qty.negate(), unitCost);
        } else if (ADJUSTMENT_VOID_TYPES.contains(type)) {
            // Voiding a stock-in adjustment takes its value back out at the cost it came in at.
            unitCost = resolveReversalCost(referenceId, productId, variantId, ADJUSTMENT_TYPES, inboundUnitCost, current);
            applyPurchaseReversal(cost, method, qty.negate(), unitCost);
        } else if (TRANSFER_VOID_TYPES.contains(type)) {
            // Voiding a transfer takes the stock back out of the destination at the cost it arrived at.
            unitCost = resolveReversalCost(referenceId, productId, variantId, TRANSFER_RECEIPT_TYPES, null, current);
            applyPurchaseReversal(cost, method, qty.negate(), unitCost);
        } else {
            unitCost = current;
            cost.setCurrentQty(nz(cost.getCurrentQty()).add(qty));
        }

        cost.setUpdatedAt(LocalDateTime.now());
        productCostRepository.save(cost);

        // Under Standard the product's Cost Price is the manually kept standard cost, so receipts leave it alone.
        if (method != CostingMethod.STANDARD
                && PURCHASE_IN_TYPES.contains(type) && qty.signum() > 0 && unitCost.signum() > 0) {
            mirrorLastPurchasePrice(productId, variantId, unitCost);
        }

        unitCost = scale(unitCost);
        return new CostResult(unitCost, total(unitCost, qty), scale(costBefore), scale(cost.getCurrentCostPrice()));
    }

    /** Current cost of a product for an org, or null when no cost record exists yet. */
    @Transactional(readOnly = true)
    public BigDecimal getCurrentCost(UUID orgId, UUID productId, UUID variantId) {
        if (orgId == null || productId == null) {
            return null;
        }
        return productCostRepository.findCurrent(orgId, productId, variantId)
                .map(ProductCost::getCurrentCostPrice)
                .orElse(null);
    }

    /** Costing method configured for the branch, falling back to the client default. */
    @Transactional(readOnly = true)
    public CostingMethod resolveMethod(UUID clientId, UUID orgId) {
        return CostingMethod.fromString(resolveConfig(clientId, orgId)
                .map(SystemConfiguration::getCostingMethod).orElse(null));
    }

    // ── inbound ──────────────────────────────────────────────────────────

    private BigDecimal resolveInboundCost(String type, UUID referenceId, UUID productId, UUID variantId,
                                          BigDecimal passed, BigDecimal current) {
        if (SALE_RESTORE_TYPES.contains(type)) {
            BigDecimal original = findOriginalCost(referenceId, productId, variantId, SALE_OUT_TYPES);
            return original != null ? original : current;
        }
        if (TRANSFER_IN_TYPES.contains(type)) {
            BigDecimal original = findOriginalCost(referenceId, productId, variantId, TRANSFER_OUT_TYPES);
            return original != null ? original : current;
        }
        if (ADJUSTMENT_VOID_TYPES.contains(type)) {
            // Voiding a stock-out adjustment returns the stock at the cost it left at.
            BigDecimal original = findOriginalCost(referenceId, productId, variantId, ADJUSTMENT_TYPES);
            return original != null ? original : current;
        }
        if (passed != null && passed.signum() > 0) {
            return passed;
        }
        return current;
    }

    void applyInbound(ProductCost cost, CostingMethod method, String type, BigDecimal inQty, BigDecimal inCost) {
        BigDecimal oldQty = nz(cost.getCurrentQty());
        BigDecimal oldCost = nz(cost.getCurrentCostPrice());
        boolean isPurchase = PURCHASE_IN_TYPES.contains(type);

        BigDecimal newCost;
        switch (method) {
            case LAST_PO -> newCost = (isPurchase || oldCost.signum() == 0) ? inCost : oldCost;
            case STANDARD -> newCost = oldCost.signum() == 0 ? inCost : oldCost;
            default -> {
                if (oldQty.signum() <= 0 || oldCost.signum() == 0) {
                    newCost = inCost;
                } else {
                    BigDecimal value = oldQty.multiply(oldCost).add(inQty.multiply(inCost));
                    newCost = value.divide(oldQty.add(inQty), COST_SCALE, RoundingMode.HALF_UP);
                }
            }
        }

        cost.setCurrentCostPrice(scale(newCost));
        cost.setCurrentQty(oldQty.add(inQty));
        cost.setCumulatedQty(nz(cost.getCumulatedQty()).add(inQty));
        cost.setCumulatedAmt(scale(nz(cost.getCumulatedAmt()).add(inQty.multiply(inCost))));
        if (isPurchase && inCost.signum() > 0) {
            cost.setLastPurchasePrice(scale(inCost));
        }
    }

    // ── purchase void (reversal of a receipt) ────────────────────────────

    /** Cost of the inbound movement being reversed: its ledger cost, else the caller's cost, else the current cost. */
    private BigDecimal resolveReversalCost(UUID referenceId, UUID productId, UUID variantId, Set<String> inboundTypes,
                                           BigDecimal passed, BigDecimal current) {
        BigDecimal original = findOriginalCost(referenceId, productId, variantId, inboundTypes);
        if (original != null) {
            return original;
        }
        return passed != null && passed.signum() > 0 ? passed : current;
    }

    void applyPurchaseReversal(ProductCost cost, CostingMethod method, BigDecimal outQty, BigDecimal receiptCost) {
        BigDecimal oldQty = nz(cost.getCurrentQty());
        BigDecimal oldCost = nz(cost.getCurrentCostPrice());
        BigDecimal newQty = oldQty.subtract(outQty);

        if (method == CostingMethod.AVERAGE_PO && newQty.signum() > 0) {
            BigDecimal value = oldQty.multiply(oldCost).subtract(outQty.multiply(receiptCost));
            if (value.signum() > 0) {
                cost.setCurrentCostPrice(value.divide(newQty, COST_SCALE, RoundingMode.HALF_UP));
            }
        }
        cost.setCurrentQty(newQty);
        cost.setCumulatedQty(nz(cost.getCumulatedQty()).subtract(outQty));
        cost.setCumulatedAmt(scale(nz(cost.getCumulatedAmt()).subtract(outQty.multiply(receiptCost))));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private BigDecimal findOriginalCost(UUID referenceId, UUID productId, UUID variantId, Set<String> types) {
        if (referenceId == null) {
            return null;
        }
        List<StockLedger> rows = stockLedgerRepository
                .findByReferenceIdAndProductIdAndTransactionTypeInOrderByTransactionDateDesc(referenceId, productId, types);
        return rows.stream()
                .filter(r -> Objects.equals(r.getVariantId(), variantId))
                .map(StockLedger::getUnitCost)
                .filter(c -> c != null && c.signum() > 0)
                .findFirst()
                .orElse(null);
    }

    /**
     * Brings the cost record up to date with what is known outside it before a movement is costed.
     * Standard: the cost is the product's manually kept Cost Price, so an edit there takes effect.
     * Other methods: a cost of 0 (the product had no Cost Price when the record was created) is replaced
     * by the Cost Price entered since, else by the last purchase receipt price in the stock ledger, so
     * stock is not issued at a cost of 0 while a real cost is known.
     */
    private void refreshKnownCost(ProductCost cost, CostingMethod method, UUID orgId, UUID productId, UUID variantId) {
        BigDecimal current = nz(cost.getCurrentCostPrice());
        if (method == CostingMethod.STANDARD) {
            BigDecimal standard = openingCost(productId, variantId);
            if (standard.signum() > 0 && standard.compareTo(current) != 0) {
                cost.setCurrentCostPrice(scale(standard));
            }
            return;
        }
        if (current.signum() != 0) {
            return;
        }
        BigDecimal known = openingCost(productId, variantId);
        if (known.signum() == 0) {
            known = lastPurchaseCost(orgId, productId, variantId);
        }
        if (known.signum() > 0) {
            cost.setCurrentCostPrice(scale(known));
        }
    }

    /** Unit cost of the most recent purchase receipt of the product in this org, or 0 when there is none. */
    private BigDecimal lastPurchaseCost(UUID orgId, UUID productId, UUID variantId) {
        List<StockLedger> rows = stockLedgerRepository
                .findTop20ByOrgIdAndProductIdAndTransactionTypeInOrderByTransactionDateDesc(orgId, productId, PURCHASE_IN_TYPES);
        if (rows == null) {
            return BigDecimal.ZERO;
        }
        return rows.stream()
                .filter(r -> Objects.equals(r.getVariantId(), variantId))
                .filter(r -> r.getQuantityChange() != null && r.getQuantityChange().signum() > 0)
                .map(StockLedger::getUnitCost)
                .filter(c -> c != null && c.signum() > 0)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private ProductCost newCostRecord(UUID clientId, UUID orgId, UUID productId, UUID variantId, CostingMethod method) {
        BigDecimal opening = openingCost(productId, variantId);
        return ProductCost.builder()
                .clientId(clientId)
                .orgId(orgId)
                .productId(productId)
                .variantId(variantId)
                .costingMethod(method)
                .currentCostPrice(scale(opening))
                .build();
    }

    /** Opening cost for a product never costed before: the manually maintained cost price. */
    private BigDecimal openingCost(UUID productId, UUID variantId) {
        if (variantId != null) {
            for (VariantPricing vp : variantPricingRepository.findByProductId(productId)) {
                if (vp.getVariantOption() != null && variantId.equals(vp.getVariantOption().getId())
                        && vp.getCostPrice() != null && vp.getCostPrice().signum() > 0) {
                    return vp.getCostPrice();
                }
            }
        }
        return productRepository.findById(productId)
                .map(Product::getCostPrice)
                .filter(c -> c != null && c.signum() > 0)
                .orElse(BigDecimal.ZERO);
    }

    /** Keeps the product's "Cost Price" field as the last purchase price (still the PO line default). */
    private void mirrorLastPurchasePrice(UUID productId, UUID variantId, BigDecimal unitCost) {
        BigDecimal price = unitCost.setScale(2, RoundingMode.HALF_UP);
        try {
            if (variantId != null) {
                for (VariantPricing vp : variantPricingRepository.findByProductId(productId)) {
                    if (vp.getVariantOption() != null && variantId.equals(vp.getVariantOption().getId())) {
                        vp.setCostPrice(price);
                        variantPricingRepository.save(vp);
                    }
                }
                return;
            }
            productRepository.findById(productId).ifPresent(p -> {
                p.setCostPrice(price);
                productRepository.save(p);
            });
        } catch (Exception e) {
            log.warn("Could not update last purchase price for product {} variant {}: {}", productId, variantId, e.getMessage());
        }
    }

    private java.util.Optional<SystemConfiguration> resolveConfig(UUID clientId, UUID orgId) {
        if (clientId == null) {
            return java.util.Optional.empty();
        }
        if (orgId != null) {
            var branch = systemConfigurationRepository.findFirstByClientIdAndOrgId(clientId, orgId);
            if (branch.isPresent()) {
                return branch;
            }
        }
        return systemConfigurationRepository.findFirstByClientIdAndOrgIdIsNull(clientId);
    }

    private static BigDecimal total(BigDecimal unit, BigDecimal qty) {
        return unit.multiply(qty).setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
