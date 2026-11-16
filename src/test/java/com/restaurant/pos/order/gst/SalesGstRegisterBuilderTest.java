package com.restaurant.pos.order.gst;

import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.dto.report.SalesGstRegisterRowDto;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.purchasing.domain.Customer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SalesGstRegisterBuilderTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private static Order sale(String status) {
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setOrderNo("SO-1");
        o.setInvoiceNo("INV-1");
        o.setOrderStatus(status);
        o.setOrderDate(Instant.parse("2026-10-05T10:00:00Z"));
        o.setOrgId(ORG);
        o.setGrandTotal(new BigDecimal("1050"));
        OrderLine l = new OrderLine();
        l.setProductId(PRODUCT);
        l.setProductName("Masala Dosa");
        l.setQuantity(new BigDecimal("10"));
        l.setUnitOfMeasure("pcs");
        l.setTaxRate(new BigDecimal("5"));
        l.setTaxAmount(new BigDecimal("50"));
        l.setLineTotal(new BigDecimal("1050"));
        l.setOrder(o);
        o.setLines(List.of(l));
        return o;
    }

    private static Organization org(String gstin) {
        Organization org = new Organization();
        org.setId(ORG);
        org.setName("Kozhikode");
        org.setGstin(gstin);
        return org;
    }

    private static Product product(String hsn) {
        Product p = new Product();
        p.setId(PRODUCT);
        p.setName("Masala Dosa");
        p.setTaxCode(hsn);
        return p;
    }

    private static Customer customer(String gstin) {
        Customer c = new Customer();
        c.setName("Acme Pvt Ltd");
        c.setGstNumber(gstin);
        return c;
    }

    private static List<SalesGstRegisterRowDto> run(Order o, Customer c) {
        Map<UUID, List<Customer>> customers = c == null ? Map.of() : Map.of(o.getId(), List.of(c));
        return SalesGstRegisterBuilder.build(List.of(o), customers,
                Map.of(PRODUCT, product("9963")), Map.of(ORG, org("32AAACR5055K1Z5")), null, 2);
    }

    @Test
    void walkInSaleIsB2csIntraStateInBranchState() {
        SalesGstRegisterRowDto r = run(sale("COMPLETED"), null).get(0);

        assertThat(r.getSupplyCategory()).isEqualTo("B2CS");
        assertThat(r.getPlaceOfSupply()).isEqualTo("32-Kerala");
        assertThat(r.getSupplyType()).isEqualTo("INTRA_STATE");
        assertThat(r.getTaxableValue()).isEqualByComparingTo("1000");
        assertThat(r.getCgstAmount()).isEqualByComparingTo("25");
        assertThat(r.getSgstAmount()).isEqualByComparingTo("25");
        assertThat(r.getHsnCode()).isEqualTo("9963");
        assertThat(r.getUqc()).isEqualTo("PCS-PIECES");
        assertThat(r.getRemarks()).isEmpty();
    }

    @Test
    void b2bCustomerInOtherStateIsInterStateIgst() {
        SalesGstRegisterRowDto r = run(sale("COMPLETED"), customer("29AAPFU0939F1ZV")).get(0);

        assertThat(r.getSupplyCategory()).isEqualTo("B2B");
        assertThat(r.getCustomerGstin()).isEqualTo("29AAPFU0939F1ZV");
        assertThat(r.getPlaceOfSupply()).isEqualTo("29-Karnataka");
        assertThat(r.getSupplyType()).isEqualTo("INTER_STATE");
        assertThat(r.getIgstAmount()).isEqualByComparingTo("50");
        assertThat(r.getCgstAmount()).isEqualByComparingTo("0");
    }

    @Test
    void companyGstinIsUsedWhenBranchHasNone() {
        Order o = sale("COMPLETED");
        SalesGstRegisterRowDto r = SalesGstRegisterBuilder.build(List.of(o), Map.of(),
                Map.of(PRODUCT, product("9963")), Map.of(ORG, org(null)), "32AAACR5055K1Z5", 2).get(0);
        assertThat(r.getBranchGstin()).isEqualTo("32AAACR5055K1Z5");
        assertThat(r.getPlaceOfSupply()).isEqualTo("32-Kerala");
    }

    @Test
    void nonCompletedSalesAreExcluded() {
        assertThat(run(sale("VOID"), null)).isEmpty();
        assertThat(run(sale("PENDING"), null)).isEmpty();
    }
}
