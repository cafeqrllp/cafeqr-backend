package com.restaurant.pos.common.tax;

import com.restaurant.pos.common.tax.TaxSplitCalculator.Breakdown;
import com.restaurant.pos.common.tax.TaxSplitCalculator.SummaryLine;
import com.restaurant.pos.common.tax.TaxSplitCalculator.TaxLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaxSplitCalculatorTest {

    private static final String RATES = "[" +
            "{\"id\":\"t5\",\"name\":\"GST 5%\",\"value\":5,\"components\":[{\"name\":\"CGST\",\"value\":2.5},{\"name\":\"SGST\",\"value\":2.5}]}," +
            "{\"id\":\"t18\",\"name\":\"GST 18%\",\"value\":18,\"components\":[{\"name\":\"CGST\",\"value\":9},{\"name\":\"SGST\",\"value\":9}]}," +
            "{\"id\":\"t12\",\"name\":\"GST 12%\",\"value\":12}]";

    private static TaxLine line(String rate, String taxable, String tax) {
        return new TaxLine(new BigDecimal(rate), new BigDecimal(taxable), new BigDecimal(tax));
    }

    private static List<String> labels(Breakdown b) {
        return b.summaryLines().stream().map(SummaryLine::label).toList();
    }

    @Test
    void splitOffGivesOneLinePerRate() {
        Breakdown b = TaxSplitCalculator.build(List.of(line("5", "200", "10")), null, RATES, false, "GST", 2);
        assertFalse(b.split());
        assertEquals(List.of("GST 5%"), labels(b));
        assertEquals(new BigDecimal("10.00"), b.summaryLines().get(0).amount());
    }

    @Test
    void splitOnUsesConfiguredNamesAndAddsUpExactly() {
        Breakdown b = TaxSplitCalculator.build(
                List.of(line("5", "200.2", "10.01"), line("18", "200", "36")), null, RATES, true, "GST", 2);
        assertTrue(b.split());
        assertEquals(List.of("CGST 2.5%", "SGST 2.5%", "CGST 9%", "SGST 9%"), labels(b));
        assertEquals(new BigDecimal("5.01"), b.summaryLines().get(0).amount());
        assertEquals(new BigDecimal("5.00"), b.summaryLines().get(1).amount());
        BigDecimal sum = b.summaryLines().stream().map(SummaryLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("46.01"), sum);
    }

    @Test
    void ruleWithoutComponentsStaysSingleLine() {
        Breakdown b = TaxSplitCalculator.build(List.of(line("12", "100", "12")), null, RATES, true, "VAT", 2);
        assertFalse(b.split());
        assertEquals(List.of("VAT 12%"), labels(b));
    }

    @Test
    void invalidComponentsAreIgnored() {
        String bad = "[{\"value\":18,\"components\":[{\"name\":\"A\",\"value\":9},{\"name\":\"B\",\"value\":8}]}]";
        Breakdown b = TaxSplitCalculator.build(List.of(line("18", "100", "18")), null, bad, true, "GST", 2);
        assertEquals(List.of("GST 18%"), labels(b));
    }

    @Test
    void fallsBackToBillTotalWhenLinesHaveNoTax() {
        Breakdown b = TaxSplitCalculator.build(List.of(line("18", "0", "0")), new BigDecimal("18"), RATES, true, "GST", 2);
        assertEquals(List.of("CGST 9%", "SGST 9%"), labels(b));
        assertEquals(new BigDecimal("9.00"), b.summaryLines().get(0).amount());
    }

    @Test
    void malformedJsonDoesNotThrow() {
        Breakdown b = TaxSplitCalculator.build(List.of(line("5", "100", "5")), null, "{oops", true, "Tax", 3);
        assertEquals(List.of("Tax 5%"), labels(b));
        assertEquals(new BigDecimal("5.000"), b.summaryLines().get(0).amount());
    }
}
