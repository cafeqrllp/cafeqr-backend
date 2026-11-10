package com.restaurant.pos.ai;

import java.util.Set;

/**
 * What an AI assistant token (scope = ai:read) may call: GET on the read endpoints the CafeQR assistant uses,
 * nothing else. Writes, backups, payroll exports, ledgers and customer lists are all refused, even though the
 * owner's own login token could reach them.
 *
 * Keep in sync with the tools in cafe-qr-ai (CafeQrTools / BusinessInfoService). A new tool that needs another
 * endpoint must add it here.
 */
public final class AiTokenPolicy {

    private static final Set<String> ALLOWED_GET_PATHS = Set.of(
            "/api/v1/clients/me",
            "/api/v1/organizations",
            "/api/v1/reports/sales-summary",
            "/api/v1/reports/item-wise",
            "/api/v1/reports/payment-breakdown",
            "/api/v1/reports/hourly",
            "/api/v1/reports/tax-summary",
            "/api/v1/reports/profit-loss",
            "/api/v1/orders/history",
            "/api/v1/inventory/stock-overview",
            "/api/v1/products",
            "/api/v1/tables/active");

    private AiTokenPolicy() {
    }

    /** Exact path match only (no prefixes), GET/HEAD only. */
    public static boolean isAllowed(String method, String path) {
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return false;
        }
        if (path == null) {
            return false;
        }
        String normalized = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return ALLOWED_GET_PATHS.contains(normalized);
    }
}
