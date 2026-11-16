package com.restaurant.pos.expense.gst;

import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.expense.domain.Expense;
import com.restaurant.pos.purchase.dto.PurchaseGstRegisterRowDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExpenseGstRegisterBuilderTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID CAT = UUID.randomUUID();

    private static Expense expense(String supplierGstin, String tax, String rate, String docStatus) {
        Expense e = Expense.builder()
                .id(UUID.randomUUID())
                .expenseNo("EXP-1")
                .categoryId(CAT)
                .expenseDate(Instant.parse("2026-10-05T10:00:00Z"))
                .amount(new BigDecimal("1180"))
                .description("Internet bill")
                .paymentMethod("CASH")
                .docStatus(docStatus)
                .supplierName("Fibre Net")
                .supplierGstin(supplierGstin)
                .billNo("FN-42")
                .hsnSac("998422")
                .gstRate(rate == null ? null : new BigDecimal(rate))
                .taxAmount(tax == null ? null : new BigDecimal(tax))
                .build();
        e.setOrgId(ORG);
        return e;
    }

    private static Organization org(String gstin) {
        Organization o = new Organization();
        o.setId(ORG);
        o.setName("Kozhikode");
        o.setGstin(gstin);
        return o;
    }

    private static List<PurchaseGstRegisterRowDto> run(Expense e, String orgGstin, String fallback) {
        return ExpenseGstRegisterBuilder.build(List.of(e), Map.of(CAT, "Utilities"), Map.of(ORG, org(orgGstin)), fallback, 2);
    }

    @Test
    void sameStateBillSplitsCgstSgstAndAllowsItc() {
        PurchaseGstRegisterRowDto r = run(expense("32AAPFU0939F1ZV", "180", "18", "COMPLETED"), "32AAACR5055K1Z5", null).get(0);

        assertThat(r.getSupplyType()).isEqualTo("INTRA_STATE");
        assertThat(r.getTaxableValue()).isEqualByComparingTo("1000");
        assertThat(r.getCgstAmount()).isEqualByComparingTo("90");
        assertThat(r.getSgstAmount()).isEqualByComparingTo("90");
        assertThat(r.getCgstRate()).isEqualByComparingTo("9");
        assertThat(r.getHsnCode()).isEqualTo("998422");
        assertThat(r.getSupplierInvoiceNo()).isEqualTo("FN-42");
        assertThat(r.getCategoryName()).isEqualTo("Utilities");
        assertThat(r.getItcEligibility()).isEqualTo("Input services");
        assertThat(r.getRemarks()).isEmpty();
    }

    @Test
    void otherStateSupplierUsesIgstWithCompanyGstinFallback() {
        PurchaseGstRegisterRowDto r = run(expense("29AAPFU0939F1ZV", "180", "18", "COMPLETED"), null, "32AAACR5055K1Z5").get(0);

        assertThat(r.getRecipientGstin()).isEqualTo("32AAACR5055K1Z5");
        assertThat(r.getSupplyType()).isEqualTo("INTER_STATE");
        assertThat(r.getIgstAmount()).isEqualByComparingTo("180");
    }

    @Test
    void expenseWithoutGstIsFlaggedAndFullyTaxable() {
        PurchaseGstRegisterRowDto r = run(expense(null, null, null, "COMPLETED"), "32AAACR5055K1Z5", null).get(0);

        assertThat(r.getTaxableValue()).isEqualByComparingTo("1180");
        assertThat(r.getTotalTax()).isEqualByComparingTo("0");
        assertThat(r.getItcEligibility()).isEqualTo("Ineligible");
        assertThat(r.getRemarks()).contains("Supplier GSTIN missing").contains("GST not captured");
    }

    @Test
    void voidExpensesAreExcluded() {
        assertThat(run(expense("32AAPFU0939F1ZV", "180", "18", "VOID"), "32AAACR5055K1Z5", null)).isEmpty();
    }
}
