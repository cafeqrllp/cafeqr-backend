package com.restaurant.pos.whatsapp.service;

import com.restaurant.pos.client.domain.Client;
import com.restaurant.pos.client.domain.Organization;
import com.restaurant.pos.common.context.TimezoneResolver;
import com.restaurant.pos.invoice.domain.Invoice;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.purchasing.domain.Currency;
import com.restaurant.pos.purchasing.repository.CurrencyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class WhatsAppBillFormatter {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH);

    /**
     * ISO-3166 alpha-2 country → { international dialling code, national mobile number length (0 = variable) }.
     * Covers the countries offered at signup; unknown countries fall back to legacy behaviour.
     */
    private static final Map<String, String[]> DIAL_CODES = Map.ofEntries(
            Map.entry("IN", new String[]{"91", "10"}),
            Map.entry("AE", new String[]{"971", "9"}),
            Map.entry("SA", new String[]{"966", "9"}),
            Map.entry("QA", new String[]{"974", "8"}),
            Map.entry("OM", new String[]{"968", "8"}),
            Map.entry("KW", new String[]{"965", "8"}),
            Map.entry("BH", new String[]{"973", "8"}),
            Map.entry("ZA", new String[]{"27", "9"}),
            Map.entry("US", new String[]{"1", "10"}),
            Map.entry("CA", new String[]{"1", "10"}),
            Map.entry("GB", new String[]{"44", "10"}),
            Map.entry("AU", new String[]{"61", "9"}),
            Map.entry("SG", new String[]{"65", "8"}),
            Map.entry("MY", new String[]{"60", "0"}),
            Map.entry("DE", new String[]{"49", "0"}),
            Map.entry("FR", new String[]{"33", "9"}),
            Map.entry("IT", new String[]{"39", "0"}),
            Map.entry("ES", new String[]{"34", "9"}),
            Map.entry("NL", new String[]{"31", "9"}),
            Map.entry("CH", new String[]{"41", "9"}),
            Map.entry("JP", new String[]{"81", "0"}),
            Map.entry("KR", new String[]{"82", "0"}),
            Map.entry("CN", new String[]{"86", "11"}),
            Map.entry("BR", new String[]{"55", "0"})
    );

    /** Fallback zones for single-timezone countries when the store has no timezone configured. */
    private static final Map<String, String> COUNTRY_ZONES = Map.ofEntries(
            Map.entry("IN", "Asia/Kolkata"),
            Map.entry("AE", "Asia/Dubai"),
            Map.entry("SA", "Asia/Riyadh"),
            Map.entry("QA", "Asia/Qatar"),
            Map.entry("OM", "Asia/Muscat"),
            Map.entry("KW", "Asia/Kuwait"),
            Map.entry("BH", "Asia/Bahrain"),
            Map.entry("ZA", "Africa/Johannesburg"),
            Map.entry("SG", "Asia/Singapore"),
            Map.entry("GB", "Europe/London")
    );

    @Autowired(required = false)
    private CurrencyRepository currencyRepository;

    @Autowired(required = false)
    private TimezoneResolver timezoneResolver;

    /**
     * Formats the short digital-bill WhatsApp message: store, customer greeting, bill number, token, date,
     * total with paid / due status. Sent as the caption of the attached invoice PDF (no web link).
     */
    public String formatDigitalBill(Order order, Invoice invoice, Organization org, Client client) {
        if (order == null) return "";

        String storeName = plain(getStoreName(org, client));
        String currency = resolveCurrencySymbol(order, org, client);
        int dp = resolveCurrencyDecimals(order, org, client);
        String billNo = (invoice != null && invoice.getInvoiceNo() != null && !invoice.getInvoiceNo().isBlank())
                ? invoice.getInvoiceNo().trim()
                : (order.getOrderNo() != null && !order.getOrderNo().isBlank() ? order.getOrderNo().trim() : null);
        Integer token = (invoice != null && invoice.getDailyBillNo() != null)
                ? invoice.getDailyBillNo()
                : order.getDailyBillNo();
        LocalDateTime date = (invoice != null && invoice.getInvoiceDate() != null)
                ? invoice.getInvoiceDate()
                : order.getCreatedAt();
        BigDecimal grandTotal = order.getGrandTotal() != null ? order.getGrandTotal() : BigDecimal.ZERO;

        StringBuilder sb = new StringBuilder();
        sb.append("🧾 *Your bill from ").append(storeName).append("*\n\n");

        String customerName = plain(order.getCustomerName());
        if (!customerName.isBlank() && !isGenericGuest(customerName)) {
            sb.append("Hi ").append(customerName).append(", thanks for visiting!\n\n");
        } else {
            sb.append("Thanks for visiting!\n\n");
        }

        if (billNo != null) {
            sb.append("Bill No: ").append(billNo).append("\n");
        }
        if (token != null) {
            sb.append("Token: #").append(token).append("\n");
        }
        if (date != null) {
            sb.append("Date: ").append(toStoreTime(date, order, org, client).format(DATE_FMT)).append("\n");
        }
        sb.append("Total: *").append(formatMoney(grandTotal, currency, dp)).append("*");
        String status = paymentStatusLabel(order);
        if (status != null) {
            sb.append(" (").append(status).append(")");
        }
        sb.append("\n");

        sb.append("\n_Powered by Cafe QR POS_");
        return sb.toString();
    }

    /**
     * Stored timestamps are UTC (JVM + Hibernate run in UTC). Converts to the store's timezone, resolved the same
     * way as the rest of the backend (branch → client), so the message shows the same time as the invoice PDF.
     */
    LocalDateTime toStoreTime(LocalDateTime utc, Order order, Organization org, Client client) {
        ZoneId zone = null;
        UUID clientId = order.getClientId() != null ? order.getClientId() : (client != null ? client.getId() : null);
        UUID orgId = order.getOrgId() != null ? order.getOrgId() : (org != null ? org.getId() : null);
        if (timezoneResolver != null && (clientId != null || orgId != null)) {
            try {
                zone = timezoneResolver.resolveTimezone(clientId, orgId);
            } catch (Exception ex) {
                log.warn("[WhatsAppBillFormatter] Could not resolve timezone for order {}: {}", order.getId(), ex.getMessage());
            }
        }
        if (zone == null || "UTC".equals(zone.getId()) || "Z".equals(zone.getId())) {
            // No timezone configured: fall back to the store country's zone (single-timezone countries only)
            String country = client != null && client.getCountry() != null ? client.getCountry().trim().toUpperCase(Locale.ROOT) : null;
            String countryZone = country != null ? COUNTRY_ZONES.get(country) : null;
            if (countryZone != null) {
                zone = ZoneId.of(countryZone);
            }
        }
        if (zone == null) {
            return utc;
        }
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime();
    }

    /** "Paid", "Partly paid", "Due – on credit", or null when the status is unknown. */
    String paymentStatusLabel(Order order) {
        String ps = order.getPaymentStatus() != null ? order.getPaymentStatus().trim().toUpperCase(Locale.ROOT) : "";
        if ("PAID".equals(ps)) return "Paid";
        if ("PARTIAL".equals(ps)) return "Partly paid";
        boolean credit = Boolean.TRUE.equals(order.getIsCredit())
                || "CREDIT".equalsIgnoreCase(order.getReference())
                || "CREDIT".equalsIgnoreCase(order.getPaymentMethod());
        if (credit) return "Due – on credit";
        return null;
    }

    /**
     * Decimal places of the currency used on the bill: order currency first, then the branch/client default.
     * Falls back to 2 when Currency Master has no entry.
     */
    public Integer resolveCurrencyDecimals(Order order, Organization org, Client client) {
        if (currencyRepository == null) {
            return 2;
        }
        try {
            if (order != null && order.getCurrencyId() != null) {
                Optional<Currency> c = currencyRepository.findById(order.getCurrencyId());
                if (c.isPresent() && c.get().getDecimalPlaces() != null) {
                    return c.get().getDecimalPlaces();
                }
            }
            UUID clientId = order != null && order.getClientId() != null
                    ? order.getClientId()
                    : (org != null && org.getClientId() != null ? org.getClientId() : (client != null ? client.getId() : null));
            UUID orgId = order != null && order.getOrgId() != null
                    ? order.getOrgId()
                    : (org != null ? org.getId() : null);
            if (clientId == null) {
                return 2;
            }
            List<Currency> defaults = orgId != null
                    ? currencyRepository.findByClientIdAndOrgIdAndIsDefaultTrue(clientId, orgId)
                    : List.of();
            if (defaults.isEmpty()) {
                defaults = currencyRepository.findByClientIdAndIsDefaultTrue(clientId);
            }
            return defaults.stream().findFirst().map(Currency::getDecimalPlaces).orElse(2);
        } catch (Exception e) {
            return 2;
        }
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

    /**
     * Legacy normalisation with no country context (used by the official OTP session).
     * Assumes India for bare 10-digit numbers — prefer {@link #normalizePhoneNumber(String, String)}.
     */
    public String normalizePhoneNumber(String rawPhone) {
        return normalizePhoneNumber(rawPhone, null);
    }

    /**
     * Normalises a phone number into WhatsApp's international digits-only format (e.g. 971501234567).
     *
     * @param countryIso2 the store's country (ISO-3166 alpha-2, e.g. "IN", "AE", "ZA"); local numbers get
     *                    this country's dialling code. When null/unknown, falls back to the legacy India rule.
     * @return digits with country code, or null when the number cannot be resolved.
     */
    public String normalizePhoneNumber(String rawPhone, String countryIso2) {
        if (rawPhone == null || rawPhone.isBlank()) return null;
        String trimmed = rawPhone.trim();
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;

        // Explicit international form: +971..., 00971...
        if (trimmed.startsWith("+")) {
            return isPlausibleInternational(digits) ? digits : null;
        }
        if (digits.startsWith("00")) {
            String intl = digits.substring(2);
            return isPlausibleInternational(intl) ? intl : null;
        }

        String[] dial = countryIso2 != null ? DIAL_CODES.get(countryIso2.trim().toUpperCase(Locale.ROOT)) : null;
        if (dial == null) {
            return legacyIndiaNormalize(digits);
        }

        String code = dial[0];
        int nationalLen = Integer.parseInt(dial[1]);

        // Already carries this country's code (e.g. 971501234567)
        if (digits.startsWith(code)) {
            int rest = digits.length() - code.length();
            if (nationalLen > 0 ? rest == nationalLen : rest >= 7) {
                return digits;
            }
        }

        // Local form: drop the trunk prefix 0 (e.g. 0501234567 → 501234567)
        String national = digits.startsWith("0") ? digits.replaceFirst("^0+", "") : digits;
        if (nationalLen > 0) {
            if (national.length() == nationalLen) {
                return code + national;
            }
        } else if (national.length() >= 7 && national.length() <= 12) {
            return code + national;
        }

        // Neither local nor this country's international form — accept only if it already looks international
        return digits.length() >= 11 && !digits.startsWith("0") && isPlausibleInternational(digits) ? digits : null;
    }

    private static boolean isPlausibleInternational(String digits) {
        return digits.length() >= 8 && digits.length() <= 15 && !digits.startsWith("0");
    }

    private static String legacyIndiaNormalize(String digits) {
        if (digits.length() < 10) return null;
        if (digits.length() == 10) {
            return "91" + digits;
        }
        if (digits.length() == 11 && digits.startsWith("0")) {
            return "91" + digits.substring(1);
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

    /** Strips WhatsApp formatting characters so names cannot break bold/italic markup. */
    private static String plain(String value) {
        if (value == null) return "";
        return value.replaceAll("[*_~`]", "").replaceAll("\\s+", " ").trim();
    }

    private String formatMoney(BigDecimal amount, String currency, int dp) {
        if (amount == null) amount = BigDecimal.ZERO;
        String formattedNum = amount.setScale(dp, RoundingMode.HALF_UP).toPlainString();
        if (currency != null && !currency.isBlank()) {
            return currency + " " + formattedNum;
        }
        return formattedNum;
    }

    private boolean isGenericGuest(String name) {
        String lower = name.toLowerCase(Locale.ROOT).trim();
        return lower.equals("guest") || lower.equals("walk-in") || lower.equals("walk in") || lower.equals("walk-in guest");
    }
}
