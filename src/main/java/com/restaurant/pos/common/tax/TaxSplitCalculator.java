package com.restaurant.pos.common.tax;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Breaks a bill's tax into the named components configured per tax rule in Tax Settings
 * (system_configurations.tax_rates_json → each rule may carry {@code components: [{name, value}]}).
 *
 * <p>Not country-specific: names and percentages come only from configuration. When splitting is
 * off, or a rule has no valid components (they must add up to the rule's rate), that rate is a
 * single line labelled with the global tax label. Lines are matched to a rule by rate value.
 *
 * <p>Mirror of {@code cafe-qr-frontend/utils/taxSplit.js}; keep both in step.
 */
@Slf4j
public final class TaxSplitCalculator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigDecimal EPS = new BigDecimal("0.0001");

    private TaxSplitCalculator() {
    }

    /** One order/invoice line's tax facts. */
    public record TaxLine(BigDecimal rate, BigDecimal taxable, BigDecimal tax) {
    }

    public record Component(String name, BigDecimal rate) {
    }

    public record Rule(BigDecimal rate, List<Component> components) {
    }

    public record ComponentAmount(String name, BigDecimal rate, BigDecimal amount) {
    }

    /** Tax for one rate, optionally split into components. {@code rate} is null when unknown. */
    public record RateGroup(BigDecimal rate, BigDecimal taxable, BigDecimal tax, List<ComponentAmount> components) {
    }

    /** A printable summary line, e.g. "CGST 9%" → 90.00. */
    public record SummaryLine(String label, String name, BigDecimal rate, BigDecimal amount) {
    }

    public record Breakdown(List<RateGroup> groups, List<SummaryLine> summaryLines, boolean split) {
    }

    /** Parses tax_rates_json; tolerant of legacy flat numbers and malformed JSON. */
    public static List<Rule> parseRules(String taxRatesJson) {
        if (taxRatesJson == null || taxRatesJson.isBlank()) return List.of();
        try {
            JsonNode root = MAPPER.readTree(taxRatesJson);
            if (root == null || !root.isArray()) return List.of();
            List<Rule> rules = new ArrayList<>();
            for (JsonNode n : root) {
                if (n == null || n.isNull()) continue;
                if (n.isNumber() || n.isTextual()) {
                    BigDecimal v = toDecimal(n);
                    if (v != null) rules.add(new Rule(v, List.of()));
                    continue;
                }
                BigDecimal value = toDecimal(n.get("value"));
                if (value == null) continue;
                List<Component> comps = new ArrayList<>();
                JsonNode cs = n.get("components");
                if (cs != null && cs.isArray()) {
                    for (JsonNode c : cs) {
                        String name = c.hasNonNull("name") ? c.get("name").asText().trim() : "";
                        BigDecimal cv = toDecimal(c.get("value"));
                        if (!name.isEmpty() && cv != null) comps.add(new Component(name, cv));
                    }
                }
                rules.add(new Rule(value, comps));
            }
            return rules;
        } catch (Exception e) {
            log.warn("Unable to parse tax rates JSON for tax split: {}", e.getMessage());
            return List.of();
        }
    }

    /** Components are usable only when present, all positive, and summing to the rule rate. */
    public static boolean hasValidComponents(Rule rule) {
        if (rule == null || rule.components() == null || rule.components().isEmpty()) return false;
        BigDecimal sum = BigDecimal.ZERO;
        for (Component c : rule.components()) {
            if (c.rate() == null || c.rate().signum() <= 0) return false;
            sum = sum.add(c.rate());
        }
        return sum.subtract(rule.rate()).abs().compareTo(EPS) < 0;
    }

    public static Breakdown build(List<TaxLine> lines, BigDecimal totalTax, String taxRatesJson,
                                  boolean splitEnabled, String taxLabel, int decimals) {
        int dp = Math.max(0, decimals);
        String label = taxLabel == null || taxLabel.isBlank() ? "Tax" : taxLabel.trim();
        List<Rule> rules = parseRules(taxRatesJson);

        // 1. Group line tax by rate (sorted ascending).
        TreeMap<BigDecimal, BigDecimal[]> byRate = new TreeMap<>();
        Set<BigDecimal> seenRates = new LinkedHashSet<>();
        if (lines != null) {
            for (TaxLine l : lines) {
                if (l == null) continue;
                BigDecimal rate = norm(l.rate());
                BigDecimal tax = l.tax() == null ? BigDecimal.ZERO : l.tax();
                if (rate.signum() > 0) seenRates.add(rate);
                if (tax.signum() == 0 && rate.signum() == 0) continue;
                BigDecimal[] acc = byRate.computeIfAbsent(rate, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                acc[0] = acc[0].add(l.taxable() == null ? BigDecimal.ZERO : l.taxable());
                acc[1] = acc[1].add(tax);
            }
        }

        List<RateGroup> raw = new ArrayList<>();
        byRate.forEach((rate, acc) -> {
            if (acc[1].abs().compareTo(EPS) < 0) return;
            raw.add(new RateGroup(rate.signum() > 0 ? rate : null,
                    acc[0].setScale(dp, RoundingMode.HALF_UP), acc[1].setScale(dp, RoundingMode.HALF_UP), List.of()));
        });

        // 2. Fallback: lines carry no tax detail — one group for the bill total.
        if (raw.isEmpty() && totalTax != null && totalTax.abs().compareTo(EPS) >= 0) {
            BigDecimal onlyRate = seenRates.size() == 1 ? seenRates.iterator().next() : null;
            raw.add(new RateGroup(onlyRate, BigDecimal.ZERO.setScale(dp), totalTax.setScale(dp, RoundingMode.HALF_UP), List.of()));
        }

        // 3. Attach components.
        boolean anySplit = false;
        List<RateGroup> groups = new ArrayList<>();
        for (RateGroup g : raw) {
            Rule rule = splitEnabled && g.rate() != null ? findRule(rules, g.rate()) : null;
            if (rule != null && hasValidComponents(rule)) {
                anySplit = true;
                groups.add(new RateGroup(g.rate(), g.taxable(), g.tax(), splitAmount(g.tax(), g.rate(), rule.components(), dp)));
            } else {
                groups.add(g);
            }
        }

        // 4. Summary lines: same-named components at the same % merge across rates.
        LinkedHashMap<String, SummaryLine> summary = new LinkedHashMap<>();
        for (RateGroup g : groups) {
            List<ComponentAmount> parts = g.components().isEmpty()
                    ? List.of(new ComponentAmount(label, g.rate(), g.tax()))
                    : g.components();
            for (ComponentAmount p : parts) {
                String key = p.name() + "|" + (p.rate() == null ? "" : p.rate().stripTrailingZeros().toPlainString());
                SummaryLine prev = summary.get(key);
                BigDecimal amount = prev == null ? p.amount() : prev.amount().add(p.amount());
                String lbl = p.rate() == null ? p.name() : p.name() + " " + formatRate(p.rate()) + "%";
                summary.put(key, new SummaryLine(lbl, p.name(), p.rate(), amount.setScale(dp, RoundingMode.HALF_UP)));
            }
        }

        return new Breakdown(groups, new ArrayList<>(summary.values()), anySplit);
    }

    public static String formatRate(BigDecimal rate) {
        if (rate == null) return "";
        return rate.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static List<ComponentAmount> splitAmount(BigDecimal tax, BigDecimal rate, List<Component> comps, int dp) {
        List<ComponentAmount> out = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < comps.size(); i++) {
            Component c = comps.get(i);
            BigDecimal amount = i == comps.size() - 1
                    ? tax.subtract(allocated).setScale(dp, RoundingMode.HALF_UP)
                    : tax.multiply(c.rate()).divide(rate, dp, RoundingMode.HALF_UP);
            allocated = allocated.add(amount);
            out.add(new ComponentAmount(c.name(), c.rate(), amount));
        }
        return out;
    }

    private static Rule findRule(List<Rule> rules, BigDecimal rate) {
        for (Rule r : rules) {
            if (r.rate().subtract(rate).abs().compareTo(EPS) < 0) return r;
        }
        return null;
    }

    private static BigDecimal norm(BigDecimal rate) {
        return rate == null ? BigDecimal.ZERO : rate.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal toDecimal(JsonNode n) {
        if (n == null || n.isNull()) return null;
        try {
            return new BigDecimal(n.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
