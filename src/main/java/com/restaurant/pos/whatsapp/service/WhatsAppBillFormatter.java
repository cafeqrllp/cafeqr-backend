package com.restaurant.pos.whatsapp.service;

import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.common.entity.SystemConfiguration;
import com.restaurant.pos.common.repository.SystemConfigurationRepository;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderLine;
import com.restaurant.pos.order.domain.Payment;
import com.restaurant.pos.order.domain.PaymentSplit;
import com.restaurant.pos.order.repository.PaymentRepository;
import com.restaurant.pos.order.repository.PaymentSplitRepository;
import com.restaurant.pos.purchasing.domain.Currency;
import com.restaurant.pos.purchasing.repository.CurrencyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class WhatsAppBillFormatter {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    @Autowired(required = false)
    private CurrencyRepository currencyRepository;

    @Autowired(required = false)
    private SystemConfigurationRepository systemConfigurationRepository;

    @Autowired(required = false)
    private PaymentRepository paymentRepository;

    @Autowired(required = false)
    private PaymentSplitRepository paymentSplitRepository;

    /**
     * Formats Order, Invoice, and Store details into the Zeev-style minimalist smart digital e-bill notification.
     * Matches the industry standard model with direct "Visit Bill" link.
     */
    public String formatZeevStyleBill(Order order, Invoice invoice, Organization org, Client client, String publicInvoiceUrl) {
        if (order == null) return "";

        String storeName = getStoreName(org, client);
        String currency = resolveCurrencySymbol(order, org, client);
        String invNo = (invoice != null && invoice.getInvoiceNo() != null) ? invoice.getInvoiceNo() : order.getOrderNo();
        Integer dailyBillNo = (invoice != null && invoice.getDailyBillNo() != null)
                ? invoice.getDailyBillNo()
                : order.getDailyBillNo();
        BigDecimal grandTotal = order.getGrandTotal() != null ? order.getGrandTotal() : BigDecimal.ZERO;

        StringBuilder sb = new StringBuilder();
        sb.append("New Bill Loaded 🧾\n\n");
        sb.append("Thank you for your recent order at *").append(storeName).append("*.\n\n");

        if (dailyBillNo != null) {
            sb.append("*Token #").append(dailyBillNo).append("* | #").append(invNo);
        } else {
            sb.append("#").append(invNo);
        }
        sb.append(" | *").append(formatMoney(grandTotal, currency)).append("* @").append(storeName).append("\n\n");

        if (publicInvoiceUrl != null && !publicInvoiceUrl.isBlank()) {
            sb.append("👉 *Visit Bill:*\n").append(publicInvoiceUrl.trim()).append("\n\n");
        }

        sb.append("Go Green – say no to paper bills 🌱\n\n");
        sb.append("*Powered by Cafe QR POS* ⚡");

        return sb.toString();
    }

    /**
     * Formats Order, Invoice, and Store details into the concrete industry-standard WhatsApp Digital Tax Invoice.
     */
    public String formatBillMessage(Order order, Invoice invoice, Organization org, Client client) {
        if (order == null) return "";

        StringBuilder sb = new StringBuilder();

        String storeName = getStoreName(org, client);
        String currency = resolveCurrencySymbol(order, org, client);
        SystemConfiguration sysConfig = resolveSystemConfiguration(order, org, client);
        String taxLabel = (sysConfig != null && sysConfig.getTaxLabelGlobal() != null && !sysConfig.getTaxLabelGlobal().isBlank())
                ? sysConfig.getTaxLabelGlobal().trim()
                : "GST";

        // 1. Header & Store Branding
        sb.append("🧾 *TAX INVOICE*\n");
        sb.append("🍽️ *").append(storeName.toUpperCase()).append("*\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        // Customer Greeting
        String customerName = order.getCustomerName();
        if (customerName != null && !customerName.isBlank() && !isGenericGuest(customerName)) {
            sb.append("Hi *").append(customerName.trim()).append("*, thank you for dining with us! 😊\n\n");
        } else {
            sb.append("Thank you for dining with us! 😊\n\n");
        }

        // Bill Meta Info
        String invNo = (invoice != null && invoice.getInvoiceNo() != null) ? invoice.getInvoiceNo() : order.getOrderNo();
        Integer dailyBillNo = (invoice != null && invoice.getDailyBillNo() != null) 
                ? invoice.getDailyBillNo() 
                : order.getDailyBillNo();

        if (dailyBillNo != null) {
            sb.append("*Token / Bill:* #").append(dailyBillNo).append("  |  *Invoice No:* #").append(invNo).append("\n");
        } else {
            sb.append("*Invoice No:* #").append(invNo).append("\n");
        }

        if (order.getOrderNo() != null && !order.getOrderNo().equals(invNo)) {
            sb.append("*Order Ref:* #").append(order.getOrderNo()).append("\n");
        }

        LocalDateTime date = (invoice != null && invoice.getInvoiceDate() != null) 
                ? invoice.getInvoiceDate() 
                : (order.getCreatedAt() != null ? order.getCreatedAt() : LocalDateTime.now());
        sb.append("*Date & Time:* ").append(date.format(DATE_FMT)).append("\n");

        // Order Type & Table
        String fulfillment = formatFulfillment(order);
        if (!fulfillment.isBlank()) {
            sb.append("*Type:* ").append(fulfillment).append("\n");
        }

        sb.append("\n*ITEMS ORDERED:*\n");
        sb.append("────────────────────────────\n");

        // 2. Line Items
        List<OrderLine> lines = order.getLines();
        if (lines != null && !lines.isEmpty()) {
            for (OrderLine line : lines) {
                if (line == null) continue;
                String prodName = line.getProductName() != null ? line.getProductName() : "Item";
                String note = line.getDescription();
                BigDecimal qty = line.getQuantity() != null ? line.getQuantity() : BigDecimal.ONE;
                BigDecimal unitPrice = line.getUnitPrice();
                BigDecimal lineTotal = line.getLineTotal() != null ? line.getLineTotal() : BigDecimal.ZERO;

                sb.append("• ").append(formatQty(qty)).append(" × *").append(prodName.trim()).append("*");
                sb.append("  ➜  ").append(formatMoney(lineTotal, currency)).append("\n");

                if (qty.compareTo(BigDecimal.ONE) > 0 && unitPrice != null) {
                    sb.append("  _(Rate: ").append(formatMoney(unitPrice, currency)).append(" each)_\n");
                }
                if (note != null && !note.isBlank()) {
                    sb.append("  _(Note: ").append(note.trim()).append(")_\n");
                }
            }
        } else {
            sb.append("• Order Items\n");
        }

        sb.append("────────────────────────────\n");

        // 3. Totals Breakdown (Option B: Gross Subtotal with tax specified right below)
        BigDecimal grossAmount = order.getGrossAmount();
        BigDecimal discount = order.getTotalDiscountAmount();
        BigDecimal subtotal = order.getTotalAmount() != null 
                ? order.getTotalAmount() 
                : (grossAmount != null ? grossAmount : order.getGrandTotal());
        BigDecimal totalTax = order.getTotalTaxAmount();
        BigDecimal roundOff = order.getRoundOffAmount();
        BigDecimal grandTotal = order.getGrandTotal() != null ? order.getGrandTotal() : BigDecimal.ZERO;

        if (discount != null && discount.compareTo(BigDecimal.ZERO) > 0 && grossAmount != null) {
            sb.append("*Gross Total:* ").append(formatMoney(grossAmount, currency)).append("\n");
            sb.append("*Discount:* -").append(formatMoney(discount, currency)).append("\n");
        }

        sb.append("*Subtotal:* ").append(formatMoney(subtotal, currency)).append("\n");

        if (totalTax != null && totalTax.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("*Includes Taxes / ").append(taxLabel).append(":* ").append(formatMoney(totalTax, currency)).append("\n");

            // 50/50 CGST & SGST breakdown for GST
            boolean isGst = "GST".equalsIgnoreCase(taxLabel);
            if (isGst) {
                BigDecimal halfTax = totalTax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                BigDecimal otherHalf = totalTax.subtract(halfTax);

                BigDecimal firstRate = null;
                if (lines != null) {
                    for (OrderLine l : lines) {
                        if (l != null && l.getTaxRate() != null && l.getTaxRate().compareTo(BigDecimal.ZERO) > 0) {
                            firstRate = l.getTaxRate();
                            break;
                        }
                    }
                }

                if (firstRate != null && firstRate.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal halfRate = firstRate.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                    sb.append("  • CGST (").append(fmtRate(halfRate)).append("%): ").append(formatMoney(halfTax, currency)).append("\n");
                    sb.append("  • SGST (").append(fmtRate(halfRate)).append("%): ").append(formatMoney(otherHalf, currency)).append("\n");
                } else {
                    sb.append("  • CGST: ").append(formatMoney(halfTax, currency)).append("\n");
                    sb.append("  • SGST: ").append(formatMoney(otherHalf, currency)).append("\n");
                }
            }
        }

        if (roundOff != null && roundOff.compareTo(BigDecimal.ZERO) != 0) {
            String sign = roundOff.compareTo(BigDecimal.ZERO) > 0 ? "+" : "-";
            sb.append("*Round Off:* ").append(sign).append(formatMoney(roundOff.abs(), currency)).append("\n");
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        // 4. Grand Total & Payment Mode (Final bill summary at bottom)
        sb.append("*TOTAL PAID:* *").append(formatMoney(grandTotal, currency)).append("*\n");

        String payMethod = order.getReference() != null && !order.getReference().isBlank() 
                ? order.getReference().toUpperCase() 
                : (order.getPaymentMethod() != null ? order.getPaymentMethod().toUpperCase() : "CASH");

        List<PaymentSplit> splits = null;
        if (paymentRepository != null && paymentSplitRepository != null && order.getId() != null) {
            try {
                List<Payment> payments = paymentRepository.findByOrderId(order.getId());
                for (Payment p : payments) {
                    if ("Y".equalsIgnoreCase(p.getIsactive()) && !"VOID".equalsIgnoreCase(p.getDocStatus())) {
                        List<PaymentSplit> psList = paymentSplitRepository.findByPaymentIdOrderByCreatedAtAsc(p.getId());
                        if (psList != null && !psList.isEmpty()) {
                            if (splits == null) splits = new ArrayList<>();
                            splits.addAll(psList);
                        } else if ("MIXED".equalsIgnoreCase(p.getPaymentMethod()) && p.getAmountPaid() != null) {
                            if (splits == null) splits = new ArrayList<>();
                            BigDecimal total = p.getAmountPaid();
                            BigDecimal half = total.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                            BigDecimal rem = total.subtract(half);
                            splits.add(PaymentSplit.builder().paymentMethod("CASH").amount(half).build());
                            splits.add(PaymentSplit.builder().paymentMethod("ONLINE").amount(rem).build());
                        } else if (payments.size() > 1 && p.getAmountPaid() != null && p.getAmountPaid().compareTo(BigDecimal.ZERO) > 0) {
                            if (splits == null) splits = new ArrayList<>();
                            splits.add(PaymentSplit.builder()
                                    .paymentMethod(p.getPaymentMethod() != null ? p.getPaymentMethod().toUpperCase() : "PAYMENT")
                                    .amount(p.getAmountPaid())
                                    .build());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (splits != null && !splits.isEmpty()) {
            sb.append("*Payment Mode:* MIXED (PAID)\n");
            sb.append("*Payment Breakdown:*\n");
            for (PaymentSplit sp : splits) {
                sb.append("  • ").append(sp.getPaymentMethod() != null ? sp.getPaymentMethod().toUpperCase() : "PAYMENT")
                  .append(": ").append(formatMoney(sp.getAmount(), currency)).append("\n");
            }
        } else {
            sb.append("*Payment Mode:* ").append(payMethod);
            if ("PAID".equalsIgnoreCase(order.getPaymentStatus()) || Boolean.TRUE.equals(order.getIsReceived())) {
                sb.append(" (PAID)");
            }
            sb.append("\n");
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        // 5. Store Details & Compliance Footer
        String address = (org != null && org.getAddress() != null && !org.getAddress().isBlank()) 
                ? org.getAddress() 
                : (client != null ? client.getAddress() : null);
        String phone = (org != null && org.getPhone() != null && !org.getPhone().isBlank()) 
                ? org.getPhone() 
                : (client != null ? client.getPhone() : null);

        if (address != null && !address.isBlank()) {
            sb.append("📍 *").append(address.trim()).append("*\n");
        }
        if (phone != null && !phone.isBlank()) {
            sb.append("📞 Contact: ").append(phone.trim()).append("\n");
        }

        // GSTIN & FSSAI
        String gstin = (org != null && org.getGstin() != null && !org.getGstin().isBlank()) 
                ? org.getGstin() 
                : (client != null ? client.getGstNumber() : null);
        String fssai = (client != null && client.getFssaiNumber() != null && !client.getFssaiNumber().isBlank()) 
                ? client.getFssaiNumber() 
                : null;

        if (gstin != null && !gstin.isBlank()) {
            sb.append("🧾 *GSTIN:* ").append(gstin.trim());
            if (fssai != null && !fssai.isBlank()) {
                sb.append("  |  *FSSAI:* ").append(fssai.trim());
            }
            sb.append("\n");
        } else if (fssai != null && !fssai.isBlank()) {
            sb.append("🧾 *FSSAI:* ").append(fssai.trim()).append("\n");
        }

        // Feedback / Google Review link
        String mapsUrl = (org != null && org.getGoogleMapsUrl() != null && !org.getGoogleMapsUrl().isBlank())
                ? org.getGoogleMapsUrl()
                : (client != null ? client.getGoogleMapsUrl() : null);
        if (mapsUrl != null && !mapsUrl.isBlank()) {
            sb.append("\n⭐ *Loved your meal? Rate us here:*\n").append(mapsUrl.trim()).append("\n");
        }

        // Custom Bill Footer from System Configuration
        if (sysConfig != null && sysConfig.getBillFooter() != null && !sysConfig.getBillFooter().isBlank()) {
            sb.append("\n_").append(sysConfig.getBillFooter().trim()).append("_\n");
        }

        sb.append("\n🌱 _Thank you for choosing a digital bill!_\n");
        sb.append("_Powered by Cafe QR POS_\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        return sb.toString();
    }

    /**
     * Resolves currency symbol strictly from the Currency Master Profile of that branch/client.
     * If no symbol is configured in Currency Master, returns "" (no default symbol provided).
     */
    public String resolveCurrencySymbol(Order order, Organization org, Client client) {
        if (currencyRepository == null) {
            return "";
        }

        try {
            UUID clientId = order != null && order.getClientId() != null 
                    ? order.getClientId() 
                    : (org != null && org.getClientId() != null ? org.getClientId() : (client != null ? client.getId() : null));
            UUID orgId = order != null && order.getOrgId() != null 
                    ? order.getOrgId() 
                    : (org != null ? org.getId() : null);

            // 1. If order explicitly references a currency_id from the Currency Master
            if (order != null && order.getCurrencyId() != null) {
                Optional<Currency> c = currencyRepository.findById(order.getCurrencyId());
                if (c.isPresent() && c.get().getSymbol() != null && !c.get().getSymbol().isBlank()) {
                    return c.get().getSymbol().trim();
                }
            }

            if (clientId != null) {
                // 2. Branch default currency in Currency Master
                if (orgId != null) {
                    List<Currency> branchDefaults = currencyRepository.findByClientIdAndOrgIdAndIsDefaultTrue(clientId, orgId);
                    if (!branchDefaults.isEmpty() && branchDefaults.get(0).getSymbol() != null && !branchDefaults.get(0).getSymbol().isBlank()) {
                        return branchDefaults.get(0).getSymbol().trim();
                    }
                    List<Currency> branchCurrencies = currencyRepository.findByClientIdAndOrgIdOrderByCodeAsc(clientId, orgId);
                    if (!branchCurrencies.isEmpty() && branchCurrencies.get(0).getSymbol() != null && !branchCurrencies.get(0).getSymbol().isBlank()) {
                        return branchCurrencies.get(0).getSymbol().trim();
                    }
                }

                // 3. Client default currency in Currency Master
                List<Currency> clientDefaults = currencyRepository.findByClientIdAndIsDefaultTrue(clientId);
                if (!clientDefaults.isEmpty() && clientDefaults.get(0).getSymbol() != null && !clientDefaults.get(0).getSymbol().isBlank()) {
                    return clientDefaults.get(0).getSymbol().trim();
                }

                // 4. Any currency under client
                List<Currency> clientCurrencies = currencyRepository.findByClientIdOrderByCodeAsc(clientId);
                if (!clientCurrencies.isEmpty() && clientCurrencies.get(0).getSymbol() != null && !clientCurrencies.get(0).getSymbol().isBlank()) {
                    return clientCurrencies.get(0).getSymbol().trim();
                }
            }
        } catch (Exception ex) {
            log.warn("[WhatsAppBillFormatter] Error looking up currency master profile: {}", ex.getMessage());
        }

        // No default symbol provided if none configured in Currency Master profile
        return "";
    }

    private SystemConfiguration resolveSystemConfiguration(Order order, Organization org, Client client) {
        if (systemConfigurationRepository == null) return null;
        try {
            UUID clientId = order != null && order.getClientId() != null 
                    ? order.getClientId() 
                    : (org != null && org.getClientId() != null ? org.getClientId() : (client != null ? client.getId() : null));
            UUID orgId = order != null && order.getOrgId() != null 
                    ? order.getOrgId() 
                    : (org != null ? org.getId() : null);

            if (clientId != null) {
                if (orgId != null) {
                    Optional<SystemConfiguration> opt = systemConfigurationRepository.findFirstByClientIdAndOrgId(clientId, orgId);
                    if (opt.isPresent()) return opt.get();
                }
                return systemConfigurationRepository.findFirstByClientIdAndOrgIdIsNull(clientId).orElse(null);
            }
        } catch (Exception ex) {
            log.warn("[WhatsAppBillFormatter] Error resolving system configuration: {}", ex.getMessage());
        }
        return null;
    }

    /**
     * Normalizes phone number into international WhatsApp format (e.g. 919847920009).
     */
    public String normalizePhoneNumber(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) return null;
        String digits = rawPhone.replaceAll("[^0-9]", "");
        if (digits.length() < 10) return null;

        // If 10 digits (Standard Indian Mobile Number without prefix), prepend 91
        if (digits.length() == 10) {
            digits = "91" + digits;
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = "91" + digits.substring(1);
        }
        return digits;
    }

    private String getStoreName(Organization org, Client client) {
        if (org != null && org.getName() != null && !org.getName().isBlank()) {
            return org.getName().trim();
        }
        if (client != null && client.getName() != null && !client.getName().isBlank()) {
            return client.getName().trim();
        }
        return "Restaurant";
    }

    private String formatFulfillment(Order order) {
        if (order.getTableNumber() != null && !order.getTableNumber().isBlank()) {
            return "🍽️ Dine-In (Table " + order.getTableNumber() + ")";
        }
        if (order.getFulfillmentType() != null && !order.getFulfillmentType().isBlank()) {
            String ft = order.getFulfillmentType().toUpperCase();
            if (ft.contains("TAKEAWAY")) return "🥡 Takeaway";
            if (ft.contains("DELIVERY")) return "🛵 Delivery";
            if (ft.contains("DINE")) return "🍽️ Dine-In";
            return order.getFulfillmentType();
        }
        return "🥡 Takeaway";
    }

    private String formatMoney(BigDecimal amount, String currency) {
        if (amount == null) amount = BigDecimal.ZERO;
        String formattedNum = fmt(amount);
        if (currency != null && !currency.isBlank()) {
            return currency + " " + formattedNum;
        }
        return formattedNum;
    }

    private String fmt(BigDecimal value) {
        if (value == null) return "0.00";
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String fmtRate(BigDecimal rate) {
        if (rate == null) return "0";
        if (rate.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
            return String.valueOf(rate.intValue());
        }
        return rate.stripTrailingZeros().toPlainString();
    }

    private String formatQty(BigDecimal qty) {
        if (qty == null) return "1";
        if (qty.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
            return String.valueOf(qty.intValue());
        }
        return qty.stripTrailingZeros().toPlainString();
    }

    private boolean isGenericGuest(String name) {
        String lower = name.toLowerCase().trim();
        return lower.equals("guest") || lower.equals("walk-in") || lower.equals("walk in") || lower.equals("walk-in guest");
    }
}
