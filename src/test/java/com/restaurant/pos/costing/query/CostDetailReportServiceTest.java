package com.restaurant.pos.costing.query;

import com.restaurant.pos.costing.dto.CostDetailReportDto;
import com.restaurant.pos.costing.repository.ProductCostRepository;
import com.restaurant.pos.inventory.domain.StockLedger;
import com.restaurant.pos.inventory.repository.StockAdjustmentRepository;
import com.restaurant.pos.inventory.repository.StockLedgerRepository;
import com.restaurant.pos.inventory.repository.StockTransferRepository;
import com.restaurant.pos.order.repository.OrderRepository;
import com.restaurant.pos.product.repository.VariantOptionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CostDetailReportServiceTest {

    private final UUID org = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final UUID whA = UUID.randomUUID();
    private final UUID whB = UUID.randomUUID();

    private StockLedger row(UUID wh, String type, String qty, String unit, String before, String after, int minute) {
        BigDecimal q = new BigDecimal(qty);
        BigDecimal u = new BigDecimal(unit);
        return StockLedger.builder()
                .id(UUID.randomUUID())
                .orgId(org)
                .productId(product)
                .warehouseId(wh)
                .transactionType(type)
                .quantityChange(q)
                .balanceAfterTransaction(BigDecimal.ZERO)
                .unitCost(u)
                .totalCost(u.multiply(q))
                .costBefore(before != null ? new BigDecimal(before) : null)
                .costAfter(after != null ? new BigDecimal(after) : null)
                .transactionDate(LocalDateTime.of(2026, 11, 1, 10, minute))
                .build();
    }

    private CostDetailReportService service(List<StockLedger> rows) {
        StockLedgerRepository ledger = mock(StockLedgerRepository.class);
        when(ledger.findByOrgIdAndProductIdAndTransactionDateBetweenOrderByTransactionDateAsc(any(), any(), any(), any()))
                .thenReturn(rows);
        ProductCostRepository costs = mock(ProductCostRepository.class);
        when(costs.findCurrent(any(), any(), any())).thenReturn(Optional.empty());
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findAllById(anyIterable())).thenReturn(List.of());
        StockTransferRepository transfers = mock(StockTransferRepository.class);
        when(transfers.findAllById(anyIterable())).thenReturn(List.of());
        StockAdjustmentRepository adjustments = mock(StockAdjustmentRepository.class);
        when(adjustments.findAllById(anyIterable())).thenReturn(List.of());
        VariantOptionRepository variants = mock(VariantOptionRepository.class);
        when(variants.findAllById(anyIterable())).thenReturn(List.of());
        return new CostDetailReportService(ledger, costs, orders, transfers, adjustments, variants);
    }

    @Test
    void totalsInAndOutAndOpeningClosingCost() {
        CostDetailReportDto report = service(List.of(
                row(whA, "PURCHASE_RECEIPT", "10", "100", "0", "100", 1),
                row(whA, "PURCHASE_RECEIPT", "10", "120", "100", "110", 2),
                row(whA, "SALE_DEDUCTION", "-4", "110", "110", "110", 3)))
                .report(null, org, product, null, null, null, null);

        assertThat(report.getLines()).hasSize(3);
        assertThat(report.getOpeningCost()).isEqualByComparingTo("0");
        assertThat(report.getClosingCost()).isEqualByComparingTo("110");
        assertThat(report.getQtyIn()).isEqualByComparingTo("20");
        assertThat(report.getQtyOut()).isEqualByComparingTo("4");
        assertThat(report.getValueIn()).isEqualByComparingTo("2200");
        assertThat(report.getValueOut()).isEqualByComparingTo("440");
    }

    @Test
    void warehouseFilterLimitsLinesButCostStaysOrgWide() {
        CostDetailReportDto report = service(List.of(
                row(whA, "PURCHASE_RECEIPT", "10", "100", "0", "100", 1),
                row(whB, "PURCHASE_RECEIPT", "10", "120", "100", "110", 2)))
                .report(null, org, product, null, whA, null, null);

        assertThat(report.getLines()).hasSize(1);
        assertThat(report.getQtyIn()).isEqualByComparingTo("10");
        assertThat(report.getClosingCost()).isEqualByComparingTo("110");
    }

    @Test
    void uncostedLegacyMovementsAreFlagged() {
        CostDetailReportDto report = service(List.of(
                row(whA, "SALE", "-2", "0", null, null, 1)))
                .report(null, org, product, null, null, null, null);

        assertThat(report.getLines().get(0).isCosted()).isFalse();
        assertThat(report.getOpeningCost()).isNull();
    }
}
