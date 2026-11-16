package com.restaurant.pos.costing.query;

import com.restaurant.pos.costing.repository.ProductCostRepository;
import com.restaurant.pos.inventory.domain.StockLedger;
import com.restaurant.pos.inventory.repository.StockLedgerRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CostingQueryServiceTest {

    private static StockLedger row(UUID ref, String type, String totalCost, boolean costed) {
        return StockLedger.builder()
                .referenceId(ref)
                .productId(UUID.randomUUID())
                .transactionType(type)
                .totalCost(new BigDecimal(totalCost))
                .unitCost(new BigDecimal(totalCost).abs())
                .costAfter(costed ? BigDecimal.TEN : null)
                .build();
    }

    @Test
    void saleCostSumsIngredientDeductionsNetOfRestores() {
        UUID recipeSale = UUID.randomUUID();
        UUID legacySale = UUID.randomUUID();
        StockLedgerRepository ledgerRepository = mock(StockLedgerRepository.class);
        when(ledgerRepository.findByReferenceIdInAndTransactionTypeIn(anyCollection(), anyCollection())).thenReturn(List.of(
                // recipe item: two ingredients consumed
                row(recipeSale, "SALE_DEDUCTION", "-30", true),
                row(recipeSale, "SALE_DEDUCTION", "-12.5", true),
                // order edited: one ingredient restored and deducted again
                row(recipeSale, "SALE_VOID_RESTORE", "12.5", true),
                row(recipeSale, "SALE_DEDUCTION", "-12.5", true),
                // movement from before costing existed: not costed, caller must fall back
                row(legacySale, "SALE_DEDUCTION", "0", false)));

        CostingQueryService service = new CostingQueryService(ledgerRepository, mock(ProductCostRepository.class));
        Map<UUID, BigDecimal> cogs = service.saleCostByOrder(List.of(recipeSale, legacySale));

        assertThat(cogs.get(recipeSale)).isEqualByComparingTo("42.5");
        assertThat(cogs).doesNotContainKey(legacySale);
    }

    @Test
    void saleWithAZeroCostIngredientFallsBackToItemCost() {
        UUID sale = UUID.randomUUID();
        StockLedgerRepository ledgerRepository = mock(StockLedgerRepository.class);
        when(ledgerRepository.findByReferenceIdInAndTransactionTypeIn(anyCollection(), anyCollection())).thenReturn(List.of(
                row(sale, "SALE_DEDUCTION", "-30", true),
                // ingredient never purchased and without a cost price: went out at 0
                row(sale, "SALE_DEDUCTION", "0", true)));

        CostingQueryService service = new CostingQueryService(ledgerRepository, mock(ProductCostRepository.class));

        assertThat(service.saleCostByOrder(List.of(sale))).doesNotContainKey(sale);
    }
}
