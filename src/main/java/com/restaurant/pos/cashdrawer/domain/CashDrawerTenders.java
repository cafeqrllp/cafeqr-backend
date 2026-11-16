package com.restaurant.pos.cashdrawer.domain;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Normalises the configured list of tender types that open the cash drawer. */
public final class CashDrawerTenders {

    public static final Set<String> SUPPORTED = Set.of("CASH", "CARD", "UPI", "ONLINE");
    public static final String DEFAULT = "CASH";

    private CashDrawerTenders() {
    }

    /** Upper-cases, de-duplicates and drops unknown tenders. Blank input falls back to CASH. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String tender = part.trim().toUpperCase(Locale.ROOT);
            if (SUPPORTED.contains(tender)) {
                out.add(tender);
            }
        }
        return out.isEmpty() ? DEFAULT : String.join(",", out);
    }
}
