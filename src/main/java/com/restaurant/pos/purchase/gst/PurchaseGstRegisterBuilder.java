package com.restaurant.pos.purchase.gst;

import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.common.gst.GstStateCodes;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.purchase.dto.PurchaseGstRegisterRowDto;
import com.restaurant.pos.purchasing.domain.Vendor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the India GST purchase register (inward supplies) from purchase orders.
 *
 * Tax split rule: supplier state (GSTIN digits 1-2) == recipient branch state → CGST + SGST/UTGST
 * (half each); different states → IGST. When either GSTIN is missing the split falls back to
 * CGST + SGST and the row is flagged in {@code remarks} for review before filing.
 *
 * Pure function of its inputs — repository lookups happen in the caller.
 */
public final class PurchaseGstRegisterBuilder {

    private static final Set<String> EXCLUDED_STATUSES = Set.of("VOID", "CANCELLED", "DRAFT");
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private PurchaseGstRegisterBuilder() {
    }

    public static boolean isFileable(Order o) {
        String status = o.getOrderStatus() == null ? "" : o.getOrderStatus().toUpperCase();
        return !EXCLUDED_STATUSES.contains(status) && o.isActive();
    }

    public static List<PurchaseGstRegisterRowDto> build(List<Order> orders,
                                                       Map<UUID, Vendor> vendors,
                                                       Map<UUID, Product> products,
                                                       Map<UUID, Organization> orgs,
                                                       String fallbackGstin,
                                                       int dp) {
        List<PurchaseGstRegisterRowDto> rows = new ArrayList<>();
        List<Order> sorted = orders.stream()
                .filter(PurchaseGstRegisterBuilder::isFileable)
                .sorted(Comparator.comparing(Order::getOrderDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        for (Order o : sorted) {
            Vendor vendor = o.getVendorId() != null ? vendors.get(o.getVendorId()) : null;
            Organization org = o.getOrgId() != null ? orgs.get(o.getOrgId()) : null;

            String supplierGstin = vendor != null ? GstStateCodes.normalize(vendor.getGstin()) : null;
            if (supplierGstin != null && supplierGstin.isBlank()) supplierGstin = null;
            String recipientGstin = org != null ? GstStateCodes.normalize(org.getGstin()) : null;
            if (recipientGstin == null || recipientGstin.isBlank()) recipientGstin = GstStateCodes.normalize(fallbackGstin);
            if (recipientGstin != null && recipientGstin.isBlank()) recipientGstin = null;

            String supplierState = GstStateCodes.stateCode(supplierGstin);
            String recipientState = GstStateCodes.stateCode(recipientGstin);
            boolean supplierValid = GstStateCodes.isValidGstin(supplierGstin);

            String registrationType = supplierGstin == null ? "Unregistered"
                    : (supplierValid ? "Registered" : "Invalid GSTIN");

            boolean interState = supplierState != null && recipientState != null && !supplierState.equals(recipientState);
            boolean utgst = GstStateCodes.isUnionTerritoryWithoutLegislature(recipientState);

            List<String> invoiceRemarks = new ArrayList<>();
            if (supplierGstin == null) invoiceRemarks.add("Supplier GSTIN missing (unregistered / B2B-URD, no ITC)");
            else if (!supplierValid) invoiceRemarks.add("Supplier GSTIN format invalid");
            if (recipientGstin == null) invoiceRemarks.add("Branch GSTIN missing");
            if (supplierGstin != null && (supplierState == null || recipientState == null)) {
                invoiceRemarks.add("State undeterminable (defaulted to CGST+SGST)");
            }
            if (o.getReference() == null || o.getReference().isBlank()) {
                invoiceRemarks.add("Supplier invoice no. missing");
            }

            List<OrderLine> lines = o.getLines() == null ? List.of()
                    : o.getLines().stream().filter(OrderLine::isActive).toList();

            BigDecimal invoiceTax = BigDecimal.ZERO;
            BigDecimal invoiceTaxable = BigDecimal.ZERO;
            for (OrderLine l : lines) {
                BigDecimal tax = scale(safe(l.getTaxAmount()), dp);
                invoiceTax = invoiceTax.add(tax);
                invoiceTaxable = invoiceTaxable.add(taxable(l, dp));
            }
            if (lines.isEmpty()) {
                invoiceTax = scale(safe(o.getTotalTaxAmount()), dp);
                invoiceTaxable = scale(safe(o.getGrandTotal()).subtract(invoiceTax).subtract(safe(o.getRoundOffAmount())), dp);
            }

            PurchaseGstRegisterRowDto.PurchaseGstRegisterRowDtoBuilder base = PurchaseGstRegisterRowDto.builder()
                    .orderId(o.getId())
                    .orderNo(o.getOrderNo())
                    .supplierInvoiceNo(o.getReference())
                    .invoiceDate(o.getOrderDate())
                    .orderStatus(o.getOrderStatus())
                    .paymentStatus(o.getPaymentStatus())
                    .paymentMethod(o.getPaymentMethod())
                    .vendorId(o.getVendorId())
                    .supplierName(vendor != null ? vendor.getName() : null)
                    .supplierGstin(supplierGstin)
                    .supplierRegistrationType(registrationType)
                    .supplierStateCode(supplierState)
                    .supplierState(GstStateCodes.stateName(supplierState))
                    .supplierAddress(vendor != null ? vendor.getAddress() : null)
                    .orgId(o.getOrgId())
                    .branchName(org != null ? org.getName() : null)
                    .recipientGstin(recipientGstin)
                    .recipientStateCode(recipientState)
                    .recipientState(GstStateCodes.stateName(recipientState))
                    // Inward supply: place of supply is normally the recipient's state.
                    .placeOfSupply(recipientState != null ? GstStateCodes.label(recipientState)
                            : GstStateCodes.label(supplierState))
                    .supplyType(interState ? "INTER_STATE" : "INTRA_STATE")
                    .reverseCharge("N")
                    .invoiceType("Regular")
                    .invoiceValue(scale(safe(o.getGrandTotal()), dp))
                    .invoiceTaxableValue(invoiceTaxable)
                    .invoiceTotalTax(invoiceTax)
                    .invoiceRoundOff(scale(safe(o.getRoundOffAmount()), dp));

            if (lines.isEmpty()) {
                rows.add(base
                        .lineNo(0)
                        .taxableValue(invoiceTaxable)
                        .totalTax(invoiceTax)
                        .remarks(joinRemarks(invoiceRemarks, List.of("No line items on invoice")))
                        .build());
                continue;
            }

            int lineNo = 0;
            for (OrderLine l : lines) {
                lineNo++;
                Product product = l.getProductId() != null ? products.get(l.getProductId()) : null;
                List<String> lineRemarks = new ArrayList<>();

                String hsn = resolveHsn(l, product);
                if (hsn == null) lineRemarks.add("HSN missing");

                String uom = l.getUnitOfMeasure();
                if ((uom == null || uom.isBlank()) && product != null && product.getUom() != null) {
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
                }

                if (utgst && !interState && tax.signum() > 0) lineRemarks.add("SGST column is UTGST");

                boolean itcAllowed = supplierValid && tax.signum() > 0;
                String itc = itcAllowed ? "Inputs" : "Ineligible";

                BigDecimal rateExTax = qty.signum() != 0
                        ? taxableValue.divide(qty, 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

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
                        .rateExTax(rateExTax)
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
                        .itcEligibility(itc)
                        .itcIgst(itcAllowed ? igst : BigDecimal.ZERO)
                        .itcCgst(itcAllowed ? cgst : BigDecimal.ZERO)
                        .itcSgst(itcAllowed ? sgst : BigDecimal.ZERO)
                        .itcCess(BigDecimal.ZERO)
                        .remarks(joinRemarks(invoiceRemarks, lineRemarks))
                        .build());
            }
        }
        return rows;
    }

    /** Line total on purchases is tax-inclusive, so taxable = lineTotal − tax (preferring the stored snapshot). */
    static BigDecimal taxable(OrderLine l, int dp) {
        if (l.getTaxableAmount() != null) return scale(l.getTaxableAmount(), dp);
        return scale(safe(l.getLineTotal()).subtract(safe(l.getTaxAmount())), dp);
    }

    /** Product master holds the HSN in taxCode; line taxCode is used only when it looks like an HSN/SAC (digits). */
    static String resolveHsn(OrderLine l, Product product) {
        if (product != null && isHsn(product.getTaxCode())) return product.getTaxCode().trim();
        if (isHsn(l.getTaxCode())) return l.getTaxCode().trim();
        return null;
    }

    private static boolean isHsn(String code) {
        return code != null && code.trim().matches("\\d{4,8}");
    }

    private static String joinRemarks(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return String.join("; ", all);
    }

    private static BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v, int dp) {
        return v.setScale(dp, RoundingMode.HALF_UP);
    }
}
