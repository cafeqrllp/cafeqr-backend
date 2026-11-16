package com.restaurant.pos.expense.gst;

import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.common.gst.GstStateCodes;
import com.restaurant.pos.expense.domain.Expense;
import com.restaurant.pos.purchase.dto.PurchaseGstRegisterRowDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the India GST inward-supply register for expenses (one row per expense bill), using the
 * same row shape as the purchase register so both can be filed / reconciled against GSTR-2B together.
 *
 * `amount` is the tax-inclusive total paid; taxable = amount − taxAmount. Same supplier/branch state →
 * CGST + SGST/UTGST; different → IGST. Missing GSTIN/HSN/bill no. is flagged in remarks.
 *
 * Pure function of its inputs — repository lookups happen in the caller.
 */
public final class ExpenseGstRegisterBuilder {

    private static final Set<String> EXCLUDED_STATUSES = Set.of("VOID", "VOIDED", "CANCELLED", "REVERSED");
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private ExpenseGstRegisterBuilder() {
    }

    public static List<PurchaseGstRegisterRowDto> build(List<Expense> expenses,
                                                        Map<UUID, String> categoryNames,
                                                        Map<UUID, Organization> orgs,
                                                        String fallbackGstin,
                                                        int dp) {
        List<PurchaseGstRegisterRowDto> rows = new ArrayList<>();
        List<Expense> sorted = expenses.stream()
                .filter(e -> e.isActive() && !EXCLUDED_STATUSES.contains(String.valueOf(e.getDocStatus()).toUpperCase()))
                .sorted(Comparator.comparing(Expense::getExpenseDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        for (Expense e : sorted) {
            Organization org = e.getOrgId() != null ? orgs.get(e.getOrgId()) : null;
            String recipientGstin = blankToNull(org != null ? GstStateCodes.normalize(org.getGstin()) : null);
            if (recipientGstin == null) recipientGstin = blankToNull(GstStateCodes.normalize(fallbackGstin));
            String supplierGstin = blankToNull(GstStateCodes.normalize(e.getSupplierGstin()));

            String supplierState = GstStateCodes.stateCode(supplierGstin);
            String recipientState = GstStateCodes.stateCode(recipientGstin);
            boolean supplierValid = GstStateCodes.isValidGstin(supplierGstin);
            boolean interState = supplierState != null && recipientState != null && !supplierState.equals(recipientState);

            BigDecimal amount = scale(safe(e.getAmount()), dp);
            BigDecimal tax = scale(safe(e.getTaxAmount()), dp);
            BigDecimal taxable = amount.subtract(tax);
            BigDecimal rate = e.getGstRate() != null ? e.getGstRate().stripTrailingZeros() : BigDecimal.ZERO;
            if (rate.scale() < 0) rate = rate.setScale(0);

            List<String> remarks = new ArrayList<>();
            if (supplierGstin == null) remarks.add("Supplier GSTIN missing (unregistered, no ITC)");
            else if (!supplierValid) remarks.add("Supplier GSTIN format invalid");
            if (recipientGstin == null) remarks.add("Branch GSTIN missing");
            if (blankToNull(e.getBillNo()) == null) remarks.add("Bill no. missing");
            if (blankToNull(e.getHsnSac()) == null) remarks.add("HSN/SAC missing");
            if (e.getGstRate() == null && e.getTaxAmount() == null) remarks.add("GST not captured");
            if (supplierGstin != null && (supplierState == null || recipientState == null)) {
                remarks.add("State undeterminable (defaulted to CGST+SGST)");
            }

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
                if (GstStateCodes.isUnionTerritoryWithoutLegislature(recipientState) && tax.signum() > 0) {
                    remarks.add("SGST column is UTGST");
                }
            }
            boolean itcAllowed = supplierValid && tax.signum() > 0;
            String category = e.getCategoryId() != null ? categoryNames.get(e.getCategoryId()) : null;

            rows.add(PurchaseGstRegisterRowDto.builder()
                    .orderId(e.getId())
                    .orderNo(e.getExpenseNo())
                    .supplierInvoiceNo(e.getBillNo())
                    .invoiceDate(e.getExpenseDate())
                    .orderStatus(e.getDocStatus())
                    .paymentStatus(e.getPaymentStatus())
                    .paymentMethod(e.getPaymentMethod())
                    .supplierName(e.getSupplierName())
                    .supplierGstin(supplierGstin)
                    .supplierRegistrationType(supplierGstin == null ? "Unregistered" : (supplierValid ? "Registered" : "Invalid GSTIN"))
                    .supplierStateCode(supplierState)
                    .supplierState(GstStateCodes.stateName(supplierState))
                    .orgId(e.getOrgId())
                    .branchName(org != null ? org.getName() : null)
                    .recipientGstin(recipientGstin)
                    .recipientStateCode(recipientState)
                    .recipientState(GstStateCodes.stateName(recipientState))
                    .placeOfSupply(recipientState != null ? GstStateCodes.label(recipientState) : GstStateCodes.label(supplierState))
                    .supplyType(interState ? "INTER_STATE" : "INTRA_STATE")
                    .reverseCharge("N")
                    .invoiceType("Regular")
                    .invoiceValue(amount)
                    .invoiceTaxableValue(taxable)
                    .invoiceTotalTax(tax)
                    .invoiceRoundOff(BigDecimal.ZERO.setScale(dp))
                    .lineNo(1)
                    .itemName(blankToNull(e.getDescription()) != null ? e.getDescription() : category)
                    .categoryName(category)
                    .hsnCode(blankToNull(e.getHsnSac()))
                    .quantity(BigDecimal.ONE)
                    .rateExTax(taxable)
                    .discountAmount(BigDecimal.ZERO.setScale(dp))
                    .taxableValue(taxable)
                    .gstRate(rate)
                    .igstRate(igstRate)
                    .igstAmount(igst)
                    .cgstRate(cgstRate)
                    .cgstAmount(cgst)
                    .sgstRate(sgstRate)
                    .sgstAmount(sgst)
                    .cessAmount(BigDecimal.ZERO)
                    .totalTax(tax)
                    .lineTotal(amount)
                    .itcEligibility(itcAllowed ? "Input services" : "Ineligible")
                    .itcIgst(itcAllowed ? igst : BigDecimal.ZERO)
                    .itcCgst(itcAllowed ? cgst : BigDecimal.ZERO)
                    .itcSgst(itcAllowed ? sgst : BigDecimal.ZERO)
                    .itcCess(BigDecimal.ZERO)
                    .remarks(String.join("; ", remarks))
                    .build());
        }
        return rows;
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
