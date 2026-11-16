package com.restaurant.pos.costing.query;

import com.restaurant.pos.costing.domain.ProductCost;
import com.restaurant.pos.costing.dto.CostDetailLineDto;
import com.restaurant.pos.costing.dto.CostDetailReportDto;
import com.restaurant.pos.costing.repository.ProductCostRepository;
import com.restaurant.pos.inventory.domain.StockLedger;
import com.restaurant.pos.inventory.repository.StockAdjustmentRepository;
import com.restaurant.pos.inventory.repository.StockLedgerRepository;
import com.restaurant.pos.inventory.repository.StockTransferRepository;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.product.repository.VariantOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Product cost report (cost detail): every costed stock movement of a product in a period, showing how
 * each receipt, sale, transfer or adjustment moved the product's current cost.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CostDetailReportService {

    private final StockLedgerRepository stockLedgerRepository;
    private final ProductCostRepository productCostRepository;
    private final OrderRepository orderRepository;
    private final StockTransferRepository stockTransferRepository;
    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final VariantOptionRepository variantOptionRepository;

    /**
     * @param variantId   only movements of this variant; null for all (base product and variants)
     * @param warehouseId only movements in this warehouse; null for all warehouses of the org
     */
    public CostDetailReportDto report(UUID clientId, UUID orgId, UUID productId, UUID variantId,
                                      UUID warehouseId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime start = from != null ? from : LocalDateTime.of(2000, 1, 1, 0, 0);
        LocalDateTime end = to != null ? to : LocalDateTime.now().plusDays(1);

        List<StockLedger> rows = stockLedgerRepository
                .findByOrgIdAndProductIdAndTransactionDateBetweenOrderByTransactionDateAsc(orgId, productId, start, end)
                .stream()
                .filter(r -> clientId == null || r.getClientId() == null || clientId.equals(r.getClientId()))
                .filter(r -> variantId == null || variantId.equals(r.getVariantId()))
                .toList();

        // Cost is kept per org and product/variant, so opening/closing come from that cost line across all
        // warehouses: the chosen variant, or the base product when no variant is chosen.
        List<StockLedger> costLine = rows.stream()
                .filter(r -> Objects.equals(r.getVariantId(), variantId))
                .filter(r -> r.getCostAfter() != null)
                .toList();
        BigDecimal opening = costLine.isEmpty() ? null : costLine.get(0).getCostBefore();
        BigDecimal closing = costLine.isEmpty() ? null : costLine.get(costLine.size() - 1).getCostAfter();

        List<StockLedger> lines = rows.stream()
                .filter(r -> warehouseId == null || warehouseId.equals(r.getWarehouseId()))
                .toList();

        Map<UUID, String> docNos = documentNumbers(lines.stream().map(StockLedger::getReferenceId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, String> variantNames = variantNames(lines.stream().map(StockLedger::getVariantId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));

        BigDecimal qtyIn = BigDecimal.ZERO, qtyOut = BigDecimal.ZERO, valueIn = BigDecimal.ZERO, valueOut = BigDecimal.ZERO;
        List<CostDetailLineDto> dtos = new java.util.ArrayList<>(lines.size());
        for (StockLedger r : lines) {
            BigDecimal qty = nz(r.getQuantityChange());
            BigDecimal total = r.getTotalCost() != null ? r.getTotalCost() : nz(r.getUnitCost()).multiply(qty);
            if (qty.signum() > 0) {
                qtyIn = qtyIn.add(qty);
                valueIn = valueIn.add(total);
            } else if (qty.signum() < 0) {
                qtyOut = qtyOut.add(qty.negate());
                valueOut = valueOut.add(total.negate());
            }
            dtos.add(CostDetailLineDto.builder()
                    .ledgerId(r.getId())
                    .transactionDate(r.getTransactionDate())
                    .transactionType(r.getTransactionType())
                    .referenceId(r.getReferenceId())
                    .documentNo(r.getReferenceId() != null ? docNos.get(r.getReferenceId()) : null)
                    .warehouseId(r.getWarehouseId())
                    .variantId(r.getVariantId())
                    .variantName(r.getVariantId() != null ? variantNames.get(r.getVariantId()) : null)
                    .quantityChange(qty)
                    .balanceAfter(r.getBalanceAfterTransaction())
                    .unitCost(r.getUnitCost())
                    .totalCost(total)
                    .costBefore(r.getCostBefore())
                    .costAfter(r.getCostAfter())
                    .costed(r.getCostAfter() != null)
                    .build());
        }

        ProductCost current = productCostRepository.findCurrent(orgId, productId, variantId).orElse(null);

        return CostDetailReportDto.builder()
                .productId(productId)
                .orgId(orgId)
                .costingMethod(current != null && current.getCostingMethod() != null ? current.getCostingMethod().name() : null)
                .currentCost(current != null ? current.getCurrentCostPrice() : null)
                .openingCost(opening)
                .closingCost(closing)
                .qtyIn(qtyIn)
                .qtyOut(qtyOut)
                .valueIn(valueIn)
                .valueOut(valueOut)
                .lines(dtos)
                .build();
    }

    /** Document numbers of the referenced orders/POs, transfers and adjustments, keyed by id. */
    private Map<UUID, String> documentNumbers(Set<UUID> ids) {
        Map<UUID, String> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        orderRepository.findAllById(ids).forEach(o -> {
            String no = firstNonBlank(o.getOrderNo(), o.getInvoiceNo(), o.getReference());
            if (no != null) map.put(o.getId(), no);
        });
        stockTransferRepository.findAllById(ids).forEach(t -> {
            if (t.getTransferNumber() != null) map.put(t.getId(), t.getTransferNumber());
        });
        stockAdjustmentRepository.findAllById(ids).forEach(a -> {
            if (a.getAdjustmentNumber() != null) map.put(a.getId(), a.getAdjustmentNumber());
        });
        return map;
    }

    private Map<UUID, String> variantNames(Set<UUID> ids) {
        Map<UUID, String> map = new HashMap<>();
        if (!ids.isEmpty()) {
            variantOptionRepository.findAllById(ids).forEach(v -> map.put(v.getId(), v.getName()));
        }
        return map;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
