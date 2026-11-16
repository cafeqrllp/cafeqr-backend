package com.restaurant.pos.costing.command;

import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.TaxType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PurchaseCostResolverTest {

    private static OrderLine line(String qty, String price, String discount, String tax, TaxType type) {
        OrderLine l = new OrderLine();
        l.setQuantity(new BigDecimal(qty));
        l.setUnitPrice(new BigDecimal(price));
        l.setDiscountAmount(new BigDecimal(discount));
        l.setTaxAmount(new BigDecimal(tax));
        l.setTaxType(type);
        return l;
    }

    @Test
    void exclusivePriceNetOfDiscountWithoutTax() {
        // 10 x 100 - 50 discount = 950 -> 95 per unit; tax of 171 is not cost
        BigDecimal cost = PurchaseCostResolver.netUnitCost(line("10", "100", "50", "171", TaxType.EXCLUSIVE));
        assertThat(cost).isEqualByComparingTo("95");
    }

    @Test
    void inclusivePriceHasTaxRemoved() {
        // 10 x 118 = 1180 including 180 tax -> 1000 -> 100 per unit
        BigDecimal cost = PurchaseCostResolver.netUnitCost(line("10", "118", "0", "180", TaxType.INCLUSIVE));
        assertThat(cost).isEqualByComparingTo("100");
    }

    @Test
    void unitPriceExTaxWinsWhenPresent() {
        OrderLine l = line("4", "118", "20", "72", TaxType.INCLUSIVE);
        l.setUnitPriceExTax(new BigDecimal("100"));
        // 4 x 100 - 20 = 380 -> 95 per unit
        assertThat(PurchaseCostResolver.netUnitCost(l)).isEqualByComparingTo("95");
    }

    @Test
    void neverNegative() {
        assertThat(PurchaseCostResolver.netUnitCost(line("1", "10", "50", "0", null)))
                .isEqualByComparingTo("0");
    }
}
