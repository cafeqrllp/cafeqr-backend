package com.restaurant.pos.purchase.gst;

import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.purchase.domain.PurchaseOrder;
import com.restaurant.pos.purchase.dto.PurchaseGstRegisterRowDto;
import com.restaurant.pos.purchasing.domain.Vendor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PurchaseGstRegisterBuilderTest {

    private static final UUID VENDOR = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private static Order order(String status, String lineTotal, String tax, String rate) {
        PurchaseOrder o = new PurchaseOrder();
        o.setId(UUID.randomUUID());
        o.setOrderNo("PO-1");
        o.setReference("SUP-INV-42");
        o.setOrderStatus(status);
        o.setOrderDate(Instant.parse("2026-10-05T10:00:00Z"));
        o.setVendorId(VENDOR);
        o.setOrgId(ORG);
        o.setGrandTotal(new BigDecimal(lineTotal));
        OrderLine l = new OrderLine();
        l.setProductId(PRODUCT);
        l.setProductName("Basmati Rice");
        l.setQuantity(new BigDecimal("10"));
        l.setUnitOfMeasure("kg");
        l.setTaxRate(new BigDecimal(rate));
        l.setTaxAmount(new BigDecimal(tax));
        l.setLineTotal(new BigDecimal(lineTotal));
        l.setOrder(o);
        o.setLines(List.of(l));
        return o;
    }

    private static Vendor vendor(String gstin) {
        Vendor v = new Vendor();
        v.setId(VENDOR);
        v.setName("Agro Traders");
        v.setGstin(gstin);
        return v;
    }

    private static Organization org(String gstin) {
        Organization org = new Organization();
        org.setId(ORG);
        org.setName("Main Branch");
        org.setGstin(gstin);
        return org;
    }

    private static Product product(String hsn) {
        Product p = new Product();
        p.setId(PRODUCT);
        p.setName("Basmati Rice");
        p.setTaxCode(hsn);
        return p;
    }

    private static List<PurchaseGstRegisterRowDto> run(Order o, String vendorGstin, String orgGstin, String hsn) {
        return PurchaseGstRegisterBuilder.build(List.of(o),
                Map.of(VENDOR, vendor(vendorGstin)), Map.of(PRODUCT, product(hsn)), Map.of(ORG, org(orgGstin)), null, 2);
    }

    @Test
    void sameStateSplitsIntoCgstAndSgst() {
        List<PurchaseGstRegisterRowDto> rows = run(order("COMPLETED", "1050", "50", "5"),
                "27AAPFU0939F1ZV", "27AAACR5055K1Z5", "1006");

        assertThat(rows).hasSize(1);
        PurchaseGstRegisterRowDto r = rows.get(0);
        assertThat(r.getSupplyType()).isEqualTo("INTRA_STATE");
        assertThat(r.getPlaceOfSupply()).isEqualTo("27-Maharashtra");
        assertThat(r.getHsnCode()).isEqualTo("1006");
        assertThat(r.getUqc()).isEqualTo("KGS-KILOGRAMS");
        assertThat(r.getTaxableValue()).isEqualByComparingTo("1000");
        assertThat(r.getCgstAmount()).isEqualByComparingTo("25");
        assertThat(r.getSgstAmount()).isEqualByComparingTo("25");
        assertThat(r.getIgstAmount()).isEqualByComparingTo("0");
        assertThat(r.getCgstRate()).isEqualByComparingTo("2.5");
        assertThat(r.getRateExTax()).isEqualByComparingTo("100");
        assertThat(r.getItcEligibility()).isEqualTo("Inputs");
        assertThat(r.getSupplierInvoiceNo()).isEqualTo("SUP-INV-42");
        assertThat(r.getRemarks()).isEmpty();
    }

    @Test
    void differentStateUsesIgst() {
        PurchaseGstRegisterRowDto r = run(order("COMPLETED", "1180", "180", "18"),
                "29AAPFU0939F1ZV", "27AAACR5055K1Z5", "2106").get(0);

        assertThat(r.getSupplyType()).isEqualTo("INTER_STATE");
        assertThat(r.getIgstAmount()).isEqualByComparingTo("180");
        assertThat(r.getIgstRate()).isEqualByComparingTo("18");
        assertThat(r.getCgstAmount()).isEqualByComparingTo("0");
        assertThat(r.getSupplierState()).isEqualTo("Karnataka");
    }

    @Test
    void unregisteredSupplierIsFlaggedAndIneligibleForItc() {
        PurchaseGstRegisterRowDto r = run(order("COMPLETED", "1050", "50", "5"),
                null, "27AAACR5055K1Z5", null).get(0);

        assertThat(r.getSupplierRegistrationType()).isEqualTo("Unregistered");
        assertThat(r.getItcEligibility()).isEqualTo("Ineligible");
        assertThat(r.getItcCgst()).isEqualByComparingTo("0");
        assertThat(r.getRemarks()).contains("Supplier GSTIN missing").contains("HSN missing");
    }

    @Test
    void draftAndVoidPurchasesAreExcluded() {
        assertThat(run(order("DRAFT", "1050", "50", "5"), "27AAPFU0939F1ZV", "27AAACR5055K1Z5", "1006")).isEmpty();
        assertThat(run(order("VOID", "1050", "50", "5"), "27AAPFU0939F1ZV", "27AAACR5055K1Z5", "1006")).isEmpty();
    }

    @Test
    void lineTaxCodeLikeGst5IsNotTreatedAsHsn() {
        Order o = order("COMPLETED", "1050", "50", "5");
        o.getLines().get(0).setTaxCode("GST_5");
        PurchaseGstRegisterRowDto r = PurchaseGstRegisterBuilder.build(List.of(o),
                Map.of(VENDOR, vendor("27AAPFU0939F1ZV")), Map.of(), Map.of(ORG, org("27AAACR5055K1Z5")), null, 2).get(0);
        assertThat(r.getHsnCode()).isNull();
    }
}
