package com.restaurant.pos.costing.command;

import com.restaurant.pos.common.entity.SystemConfiguration;
import com.restaurant.pos.common.repository.SystemConfigurationRepository;
import com.restaurant.pos.costing.domain.CostResult;
import com.restaurant.pos.costing.domain.ProductCost;
import com.restaurant.pos.costing.repository.ProductCostRepository;
import com.restaurant.pos.inventory.domain.StockLedger;
import com.restaurant.pos.inventory.repository.StockLedgerRepository;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.product.repository.VariantPricingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Costing engine rules, driven through a small in-memory ledger so receipt and reversal lookups
 * behave like the real stock_ledgers table.
 */
class CostingServiceTest {

    private final UUID clientId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    private ProductCostRepository productCostRepository;
    private ProductRepository productRepository;
    private StockLedgerRepository stockLedgerRepository;
    private CostingService service;

    private ProductCost stored;
    private final List<StockLedger> ledger = new ArrayList<>();
    private SystemConfiguration config;

    @BeforeEach
    void setUp() {
        productCostRepository = mock(ProductCostRepository.class);
        stockLedgerRepository = mock(StockLedgerRepository.class);
        productRepository = mock(ProductRepository.class);
        SystemConfigurationRepository configRepository = mock(SystemConfigurationRepository.class);

        config = SystemConfiguration.builder().clientId(clientId).costingMethod("AVERAGE_PO").build();
        when(configRepository.findFirstByClientIdAndOrgId(clientId, orgId)).thenReturn(Optional.empty());
        when(configRepository.findFirstByClientIdAndOrgIdIsNull(clientId)).thenAnswer(i -> Optional.of(config));

        when(productCostRepository.findForUpdate(eq(orgId), eq(productId), isNull()))
                .thenAnswer(i -> Optional.ofNullable(stored));
        when(productCostRepository.save(any(ProductCost.class))).thenAnswer(i -> {
            stored = i.getArgument(0);
            return stored;
        });
        when(stockLedgerRepository.findByReferenceIdAndProductIdAndTransactionTypeInOrderByTransactionDateDesc(any(), any(), anyCollection()))
                .thenAnswer(i -> {
                    UUID ref = i.getArgument(0);
                    UUID pid = i.getArgument(1);
                    Collection<String> types = i.getArgument(2);
                    List<StockLedger> rows = new ArrayList<>(ledger.stream()
                            .filter(r -> ref.equals(r.getReferenceId()) && pid.equals(r.getProductId())
                                    && types.contains(r.getTransactionType()))
                            .toList());
                    Collections.reverse(rows);
                    return rows;
                });
        when(productRepository.findById(productId)).thenReturn(Optional.of(Product.builder().build()));

        service = new CostingService(productCostRepository, stockLedgerRepository, productRepository,
                mock(VariantPricingRepository.class), configRepository);
    }

    private CostResult move(String type, UUID ref, String qty, String passedCost) {
        CostResult r = service.applyMovement(clientId, orgId, productId, null, new BigDecimal(qty), type, ref,
                passedCost != null ? new BigDecimal(passedCost) : null);
        ledger.add(StockLedger.builder().referenceId(ref).productId(productId).transactionType(type)
                .quantityChange(new BigDecimal(qty)).unitCost(r.unitCost()).totalCost(r.totalCost())
                .costAfter(r.costAfter()).build());
        return r;
    }

    private static void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(expected);
    }

    @Test
    void weightedAverageAcrossTwoReceipts() {
        move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "100");
        CostResult second = move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "120");

        assertMoney(second.costBefore(), "100");
        assertMoney(second.costAfter(), "110");
        assertMoney(stored.getCurrentQty(), "20");
        assertMoney(stored.getLastPurchasePrice(), "120");
    }

    @Test
    void saleIsCostedAtCurrentAverageAndIgnoresPassedCost() {
        move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "100");
        move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "120");

        CostResult sale = move("SALE_DEDUCTION", UUID.randomUUID(), "-5", "0");

        assertMoney(sale.unitCost(), "110");
        assertMoney(sale.totalCost(), "-550");
        assertMoney(sale.costAfter(), "110");
        assertMoney(stored.getCurrentQty(), "15");
    }

    @Test
    void voidingAReceiptRemovesItsValueAtTheOriginalCost() {
        move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "100");
        UUID secondPo = UUID.randomUUID();
        move("PURCHASE_RECEIPT", secondPo, "10", "120");

        // The caller passes a different price; the original receipt cost (120) must be used.
        CostResult reversal = move("PURCHASE_VOID", secondPo, "-10", "999");

        assertMoney(reversal.unitCost(), "120");
        assertMoney(reversal.costAfter(), "100");
        assertMoney(stored.getCurrentQty(), "10");
    }

    @Test
    void saleVoidRestoreReturnsStockAtTheOriginalSaleCost() {
        move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "100");
        UUID sale = UUID.randomUUID();
        move("SALE_DEDUCTION", sale, "-4", null);
        move("PURCHASE_RECEIPT", UUID.randomUUID(), "6", "130"); // (6*100 + 6*130) / 12 = 115

        CostResult restore = move("SALE_VOID_RESTORE", sale, "4", "0");

        assertMoney(restore.unitCost(), "100");
        assertMoney(restore.costAfter(), "111.25"); // (12*115 + 4*100) / 16
        assertMoney(stored.getCurrentQty(), "16");
    }

    @Test
    void transferBetweenWarehousesOfSameOrgKeepsCost() {
        move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "100");
        UUID transfer = UUID.randomUUID();

        CostResult out = move("TRANSFER_OUT", transfer, "-4", "0");
        CostResult in = move("TRANSFER_IN", transfer, "4", "0");

        assertMoney(out.unitCost(), "100");
        assertMoney(in.unitCost(), "100");
        assertMoney(in.costAfter(), "100");
        assertMoney(stored.getCurrentQty(), "10");
    }

    @Test
    void receiptIntoNegativeStockTakesTheReceiptCost() {
        move("SALE_DEDUCTION", UUID.randomUUID(), "-3", null);
        CostResult receipt = move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "80");

        assertMoney(receipt.costAfter(), "80");
        assertMoney(stored.getCurrentQty(), "7");
    }

    @Test
    void lastPoMethodUsesLatestReceiptPrice() {
        config.setCostingMethod("LAST_PO");
        move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "100");
        CostResult second = move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "120");

        assertMoney(second.costAfter(), "120");
    }

    @Test
    void standardMethodIgnoresReceiptsOnceCostIsSet() {
        config.setCostingMethod("STANDARD");
        when(productRepository.findById(productId))
                .thenReturn(Optional.of(Product.builder().costPrice(new BigDecimal("90")).build()));

        CostResult receipt = move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "120");
        CostResult sale = move("SALE_DEDUCTION", UUID.randomUUID(), "-1", null);

        assertMoney(receipt.costAfter(), "90");
        assertMoney(sale.unitCost(), "90");
    }

    @Test
    void openingCostComesFromManualCostPrice() {
        when(productRepository.findById(productId))
                .thenReturn(Optional.of(Product.builder().costPrice(new BigDecimal("50")).build()));

        CostResult sale = move("SALE_DEDUCTION", UUID.randomUUID(), "-2", null);

        assertMoney(sale.unitCost(), "50");
        assertMoney(sale.totalCost(), "-100");
    }

    @Test
    void purchaseReceiptUpdatesProductLastPurchasePrice() {
        Product product = Product.builder().costPrice(new BigDecimal("10")).build();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        move("PURCHASE_RECEIPT", UUID.randomUUID(), "5", "12.345");

        assertMoney(product.getCostPrice(), "12.35");
        verify(productRepository).save(product);
    }

    @Test
    void movementWithoutOrgIsNotCosted() {
        CostResult r = service.applyMovement(clientId, null, productId, null, new BigDecimal("2"),
                "PURCHASE_RECEIPT", UUID.randomUUID(), new BigDecimal("7"));

        assertMoney(r.unitCost(), "7");
        assertThat(r.costAfter()).isNull();
        verifyNoInteractions(productCostRepository);
    }

    @Test
    void standardMethodDoesNotOverwriteProductCostPrice() {
        config.setCostingMethod("STANDARD");
        Product product = Product.builder().costPrice(new BigDecimal("90")).build();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "120");

        assertMoney(product.getCostPrice(), "90");
        verify(productRepository, never()).save(any());
        assertMoney(stored.getLastPurchasePrice(), "120");
    }

    @Test
    void zeroCostPicksUpCostPriceEnteredLater() {
        CostResult first = move("SALE_DEDUCTION", UUID.randomUUID(), "-1", null);
        assertMoney(first.unitCost(), "0");

        when(productRepository.findById(productId))
                .thenReturn(Optional.of(Product.builder().costPrice(new BigDecimal("50")).build()));
        CostResult second = move("SALE_DEDUCTION", UUID.randomUUID(), "-1", null);

        assertMoney(second.unitCost(), "50");
        assertMoney(second.costAfter(), "50");
    }

    @Test
    void zeroCostFallsBackToLastPurchaseReceiptInLedger() {
        // Purchase received before costing existed: in the ledger, but the cost record started at 0.
        when(stockLedgerRepository.findTop20ByOrgIdAndProductIdAndTransactionTypeInOrderByTransactionDateDesc(
                eq(orgId), eq(productId), anyCollection()))
                .thenReturn(List.of(StockLedger.builder().productId(productId).transactionType("PURCHASE")
                        .quantityChange(new BigDecimal("10")).unitCost(new BigDecimal("115")).build()));

        CostResult sale = move("SALE_DEDUCTION", UUID.randomUUID(), "-2", null);

        assertMoney(sale.unitCost(), "115");
        assertMoney(sale.totalCost(), "-230");
    }

    @Test
    void standardMethodFollowsEditedCostPrice() {
        config.setCostingMethod("STANDARD");
        when(productRepository.findById(productId))
                .thenReturn(Optional.of(Product.builder().costPrice(new BigDecimal("90")).build()));
        move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "120");

        when(productRepository.findById(productId))
                .thenReturn(Optional.of(Product.builder().costPrice(new BigDecimal("95")).build()));
        CostResult sale = move("SALE_DEDUCTION", UUID.randomUUID(), "-1", null);

        assertMoney(sale.unitCost(), "95");
    }

    @Test
    void voidingAStockInAdjustmentRemovesItsValue() {
        UUID adjustment = UUID.randomUUID();
        move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "100");
        assertMoney(move("ADJUSTMENT", adjustment, "10", "200").costAfter(), "150");

        CostResult voided = move("ADJUSTMENT_VOID", adjustment, "-10", "200");

        assertMoney(voided.unitCost(), "200");
        assertMoney(voided.costAfter(), "100");
    }

    @Test
    void voidingAStockOutAdjustmentReturnsStockAtTheCostItLeftAt() {
        UUID adjustment = UUID.randomUUID();
        move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "100");
        move("ADJUSTMENT", adjustment, "-4", null);
        assertMoney(move("PURCHASE_RECEIPT", UUID.randomUUID(), "6", "200").costAfter(), "150");

        CostResult voided = move("ADJUSTMENT_VOID", adjustment, "4", null);

        assertMoney(voided.unitCost(), "100");
        assertMoney(voided.costAfter(), "137.5");
    }

    @Test
    void voidingATransferLeavesTheCostAsIfItNeverHappened() {
        UUID transfer = UUID.randomUUID();
        move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "100");
        move("TRANSFER_OUT", transfer, "-4", null);
        move("TRANSFER_IN", transfer, "4", null);
        assertMoney(move("PURCHASE_RECEIPT", UUID.randomUUID(), "10", "200").costAfter(), "150");

        move("TRANSFER_VOID_REVERT", transfer, "4", null);
        CostResult out = move("TRANSFER_VOID_REVERT", transfer, "-4", null);

        assertMoney(out.unitCost(), "100");
        assertMoney(out.costAfter(), "150");
    }
}
