package com.restaurant.pos.order.gst;

import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.common.gst.GstStateCodes;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.dto.report.SalesGstRegisterRowDto;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.purchasing.domain.Customer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the India GST sales register (outward supplies, GSTR-1) from completed sale orders.
 *
 * B2B when a linked customer carries a GSTIN; otherwise B2CS. Place of supply is the customer's
 * state for B2B (from GSTIN) and the branch's state for B2C counter sales. Same state → CGST +
 * SGST/UTGST (half each); different state → IGST. Missing GSTIN/HSN data is flagged in remarks.
 *
 * Pure function of its inputs — repository lookups happen in the caller.
 */
public final class SalesGstRegisterBuilder {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private SalesGstRegisterBuilder() {
    }

    public static List<SalesGstRegisterRowDto> build(List<Order> orders,
                                                     Map<UUID, List<Customer>> customersByOrder,
                                                     Map<UUID, Product> products,
                                                     Map<UUID, Organization> orgs,
                                                     String fallbackGstin,
                                                     int dp) {
        List<SalesGstRegisterRowDto> rows = new ArrayList<>();
        List<Order> sorted = orders.stream()
                .filter(o -> "COMPLETED".equalsIgnoreCase(o.getOrderStatus()) && o.isActive())
                .sorted(Comparator.comparing(Order::getOrderDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        for (Order o : sorted) {
            Organization org = o.getOrgId() != null ? orgs.get(o.getOrgId()) : null;
            String branchGstin = blankToNull(org != null ? GstStateCodes.normalize(org.getGstin()) : null);
            if (branchGstin == null) branchGstin = blankToNull(GstStateCodes.normalize(fallbackGstin));
            String branchState = GstStateCodes.stateCode(branchGstin);

            Customer customer = null;
            Customer b2bCustomer = null;
            for (Customer c : customersByOrder.getOrDefault(o.getId(), List.of())) {
                if (customer == null) customer = c;
                if (b2bCustomer == null && blankToNull(c.getGstNumber()) != null) b2bCustomer = c;
            }
            Customer billTo = b2bCustomer != null ? b2bCustomer : customer;
            String customerGstin = b2bCustomer != null ? GstStateCodes.normalize(b2bCustomer.getGstNumber()) : null;
            String customerState = GstStateCodes.stateCode(customerGstin);
            boolean b2b = customerGstin != null;

            String posState = b2b && customerState != null ? customerState : branchState;
            boolean interState = posState != null && branchState != null && !posState.equals(branchState);
            boolean utgst = GstStateCodes.isUnionTerritoryWithoutLegislature(branchState);

            List<String> invoiceRemarks = new ArrayList<>();
            if (branchGstin == null) invoiceRemarks.add("Branch GSTIN missing");
            if (b2b && !GstStateCodes.isValidGstin(customerGstin)) invoiceRemarks.add("Customer GSTIN format invalid");
            if (posState == null) invoiceRemarks.add("Place of supply undeterminable (defaulted to CGST+SGST)");
            if (blankToNull(o.getInvoiceNo()) == null) invoiceRemarks.add("Invoice no. missing (order no. used)");

            List<OrderLine> lines = o.getLines() == null ? List.of()
                    : o.getLines().stream().filter(OrderLine::isActive).toList();

            BigDecimal invoiceTax = BigDecimal.ZERO;
            BigDecimal invoiceTaxable = BigDecimal.ZERO;
            for (OrderLine l : lines) {
                invoiceTax = invoiceTax.add(scale(safe(l.getTaxAmount()), dp));
                invoiceTaxable = invoiceTaxable.add(taxable(l, dp));
            }

            SalesGstRegisterRowDto.SalesGstRegisterRowDtoBuilder base = SalesGstRegisterRowDto.builder()
                    .orderId(o.getId())
                    .invoiceNo(blankToNull(o.getInvoiceNo()) != null ? o.getInvoiceNo() : o.getOrderNo())
                    .orderNo(o.getOrderNo())
                    .invoiceDate(o.getOrderDate())
                    .paymentMethod(o.getPaymentMethod())
                    .orgId(o.getOrgId())
                    .branchName(org != null ? org.getName() : null)
                    .branchGstin(branchGstin)
                    .branchStateCode(branchState)
                    .branchState(GstStateCodes.stateName(branchState))
                    .customerName(billTo != null ? billTo.getName() : null)
                    .customerGstin(customerGstin)
                    .supplyCategory(b2b ? "B2B" : "B2CS")
                    .customerStateCode(customerState)
                    .customerState(GstStateCodes.stateName(customerState))
                    .placeOfSupply(GstStateCodes.label(posState))
                    .supplyType(interState ? "INTER_STATE" : "INTRA_STATE")
                    .reverseCharge("N")
                    .invoiceType("Regular")
                    .invoiceValue(scale(safe(o.getGrandTotal()), dp))
                    .invoiceTaxableValue(invoiceTaxable)
                    .invoiceTotalTax(invoiceTax)
                    .invoiceDiscount(scale(safe(o.getTotalDiscountAmount()), dp));

            if (lines.isEmpty()) {
                List<String> r = new ArrayList<>(invoiceRemarks);
                r.add("No line items on invoice");
                rows.add(base.lineNo(0).remarks(String.join("; ", r)).build());
                continue;
            }

            int lineNo = 0;
            for (OrderLine l : lines) {
                lineNo++;
                Product product = l.getProductId() != null ? products.get(l.getProductId()) : null;
                List<String> remarks = new ArrayList<>(invoiceRemarks);

                String hsn = resolveHsn(l, product);
                if (hsn == null) remarks.add("HSN missing");

                String uom = blankToNull(l.getUnitOfMeasure());
                if (uom == null && product != null && product.getUom() != null) {
                    uom = product.getUom().getShortName() != null ? product.getUom().getShortName() : product.getUom().getName();
                }

                BigDecimal qty = safe(l.getQuantity());
                BigDecimal taxableValue = taxable(l, dp);
                BigDecimal tax = scale(safe(l.getTaxAmount()), dp);
                BigDecimal rate = l.getTaxSnapshotRate() != null ? l.getTaxSnapshotRate() : safe(l.getTaxRate());
                rate = rate.stripTrailingZeros();
                if (rate.scale() < 0) rate = rate.setScale(0);

                BigDecimal igst = BigDecimal.ZERO, cgst = BigDecimal.ZERO, sgst = BigDecimal.ZERO;
                BigDecimal igstRate = BigDecimal.ZERO, cgstRate = BigDecimal.ZERO, sgstRate = BigDecimal.ZERO;
                if (interState) {
                    igst = tax;
                    igstRate = rate;
                } else {
                    cgst = tax.divide(TWO, dp, RoundingMode.HALF_UP);
                    sgst = tax.subtract(cgst);
                    cgstRate = rate.divide(TWO, 4, RoundingMode.HALF_UP).stripTrailingZeros();
                    sgstRate = cgstRate;
                    if (utgst && tax.signum() > 0) remarks.add("SGST column is UTGST");
                }

                rows.add(base
                        .lineNo(lineNo)
                        .productId(l.getProductId())
                        .productCode(product != null ? product.getProductCode() : null)
                        .itemName(l.getProductName() != null ? l.getProductName() : (product != null ? product.getName() : null))
                        .categoryName(l.getCategoryName() != null ? l.getCategoryName()
                                : (product != null && product.getCategory() != null ? product.getCategory().getName() : null))
                        .hsnCode(hsn)
                        .unitOfMeasure(uom)
                        .uqc(GstStateCodes.uqc(uom))
                        .quantity(qty)
                        .rateExTax(qty.signum() != 0 ? taxableValue.divide(qty, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                        .discountAmount(scale(safe(l.getDiscountAmount()), dp))
                        .taxableValue(taxableValue)
                        .gstRate(rate)
                        .igstRate(igstRate)
                        .igstAmount(igst)
                        .cgstRate(cgstRate)
                        .cgstAmount(cgst)
                        .sgstRate(sgstRate)
                        .sgstAmount(sgst)
                        .cessAmount(BigDecimal.ZERO)
                        .totalTax(tax)
                        .lineTotal(scale(safe(l.getLineTotal()), dp))
                        .remarks(String.join("; ", remarks))
                        .build());
            }
        }
        return rows;
    }

    /** Line total is tax-inclusive, so taxable = lineTotal − tax (preferring the stored GST snapshot). */
    static BigDecimal taxable(OrderLine l, int dp) {
        if (l.getTaxableAmount() != null) return scale(l.getTaxableAmount(), dp);
        return scale(safe(l.getLineTotal()).subtract(safe(l.getTaxAmount())), dp);
    }

    /** Product master holds the HSN in taxCode; line taxCode is used only when it looks like an HSN/SAC. */
    static String resolveHsn(OrderLine l, Product product) {
        if (product != null && isHsn(product.getTaxCode())) return product.getTaxCode().trim();
        if (isHsn(l.getTaxCode())) return l.getTaxCode().trim();
        return null;
    }

    private static boolean isHsn(String code) {
        return code != null && code.trim().matches("\\d{4,8}");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v, int dp) {
        return v.setScale(dp, RoundingMode.HALF_UP);
    }
}
