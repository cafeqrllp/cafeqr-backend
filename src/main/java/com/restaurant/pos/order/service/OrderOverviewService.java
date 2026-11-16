package com.restaurant.pos.order.service;

import com.restaurant.pos.client.repository.TerminalRepository;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.service.CurrencyScaleService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.order.dto.report.OrderOverviewQuery;
import com.restaurant.pos.order.dto.report.OrderOverviewResponse;
import com.restaurant.pos.order.dto.report.OrderOverviewResponse.Bucket;
import com.restaurant.pos.order.dto.report.OrderOverviewResponse.ItemBucket;
import com.restaurant.pos.order.dto.report.OrderOverviewRowDto;
import com.restaurant.pos.order.dto.report.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Business Overview (dashboard) queries.
 *
 * <p>SALE summaries longer than 36 hours read finished days from the pre-aggregated
 * {@code order_stats_daily} table (maintained by {@link OrderStatsRollupService}) and only
 * the remainder live from {@code orders}: today, days not yet rolled up, days with pending
 * changes, and partial days at the range edges. Both parts produce the same metric rows,
 * which are merged here, so the result is identical to a fully live query.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, timeout = 15)
public class OrderOverviewService {

    private static final Duration MAX_RANGE = Duration.ofDays(400);
    private static final Duration HOURLY_TREND_LIMIT = Duration.ofHours(36);
    private static final java.util.regex.Pattern TZ_NAME =
            java.util.regex.Pattern.compile("^[A-Za-z_]+(/[A-Za-z0-9_+\\-]+)*$");

    /** Same grouping the screen always used: PAID folds into COMPLETED, anything open is ORDERED. */
    static final String STATUS_GROUP =
            "CASE WHEN UPPER(o.order_status) IN ('COMPLETED','PAID') THEN 'COMPLETED' " +
            "WHEN UPPER(o.order_status) = 'BILLED' THEN 'BILLED' " +
            "WHEN UPPER(o.order_status) = 'CANCELLED' THEN 'CANCELLED' " +
            "ELSE 'ORDERED' END";

    static final String FULFILLMENT_GROUP =
            "CASE WHEN NULLIF(TRIM(o.table_number), '') IS NOT NULL THEN 'DINE_IN' " +
            "WHEN UPPER(o.fulfillment_type) = 'TAKEAWAY' THEN 'TAKEAWAY' " +
            "WHEN UPPER(o.fulfillment_type) = 'DELIVERY' THEN 'DELIVERY' " +
            "ELSE 'DINE_IN' END";

    /** Active, non-void orders (shared with the rollup job so both count the same orders). */
    static final String ACTIVE_FILTER =
            "o.isactive = 'Y' " +
            "AND COALESCE(UPPER(o.order_status), '') NOT IN ('VOID', 'VOIDED') " +
            "AND o.order_no NOT LIKE '%\\_VOID\\_%' ESCAPE '\\' ";

    private static final String UUID_RE =
            "'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'";

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "date", "o.order_date",
            "orderNo", "o.order_no",
            "status", "sg",
            "createdBy", "created_name",
            "updatedBy", "updated_name",
            "amount", "amt");

    private final NamedParameterJdbcTemplate jdbc;
    private final TerminalRepository terminalRepository;
    private final BranchContextService branchContext;
    private final CurrencyScaleService currencyScale;
    private final OrderStatsRollupService rollups;

    // ─────────────────────────────────────────────────────────────────────
    // Summary
    // ─────────────────────────────────────────────────────────────────────

    public OrderOverviewResponse getSummary(OrderOverviewQuery query) {
        Scope scope = resolveScope(query);
        boolean isSale = "SALE".equals(scope.orderType());
        boolean hourly = Duration.between(scope.from(), scope.to()).compareTo(HOURLY_TREND_LIMIT) <= 0;

        List<Interval> live = List.of(new Interval(scope.from(), scope.to(), true));
        List<LocalDate> rolledUp = List.of();
        if (isSale && !hourly && scope.orgId() != null && rollups.isEnabled()) {
            Plan plan = planRollups(scope);
            live = plan.live();
            rolledUp = plan.days();
        }

        Acc acc = new Acc();
        // Transaction-scoped: lets hash aggregates and sorts stay in memory for large ranges.
        jdbc.getJdbcTemplate().execute("SET LOCAL work_mem = '64MB'");
        if (!live.isEmpty()) runLive(scope, live, isSale, hourly, acc);
        if (!rolledUp.isEmpty()) runRollup(scope, rolledUp, acc);
        return acc.toResponse(scope.decimalPlaces(), hourly);
    }

    /**
     * Splits the range into rolled-up whole days and live intervals. A day is served from
     * rollups only when it lies fully inside the range, was computed in the requested
     * timezone, and has no pending change in the trigger log.
     */
    private Plan planRollups(Scope scope) {
        ZoneId zone = ZoneId.of(scope.zone());
        LocalDate first = scope.from().atZone(zone).toLocalDate();
        LocalDate last = scope.to().atZone(zone).toLocalDate();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("c", scope.clientId()).addValue("o", scope.orgId()).addValue("tz", scope.zone())
                .addValue("a", Date.valueOf(first)).addValue("b", Date.valueOf(last))
                .addValue("ua", Date.valueOf(first.minusDays(1))).addValue("ub", Date.valueOf(last.plusDays(1)));

        Set<LocalDate> covered = new HashSet<>(jdbc.query(
                "SELECT stat_date FROM order_stats_day WHERE client_id = :c AND org_id = :o " +
                "AND stat_date BETWEEN :a AND :b AND tz = :tz",
                p, (rs, i) -> rs.getDate(1).toLocalDate()));
        if (covered.isEmpty()) return new Plan(List.of(new Interval(scope.from(), scope.to(), true)), List.of());
        Set<LocalDate> dirtyUtc = new HashSet<>(jdbc.query(
                "SELECT DISTINCT utc_date FROM order_stats_dirty WHERE client_id = :c AND org_id = :o " +
                "AND utc_date BETWEEN :ua AND :ub",
                p, (rs, i) -> rs.getDate(1).toLocalDate()));

        List<LocalDate> days = new ArrayList<>();
        List<Interval> live = new ArrayList<>();
        Instant cursor = scope.from();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            Instant ds = d.atStartOfDay(zone).toInstant();
            Instant de = d.plusDays(1).atStartOfDay(zone).toInstant();
            boolean whole = !ds.isBefore(scope.from()) && !de.minusNanos(1000).isAfter(scope.to());
            if (!whole || !covered.contains(d) || touchesDirty(ds, de, dirtyUtc)) continue;
            if (ds.isAfter(cursor)) live.add(new Interval(cursor, ds, false));
            days.add(d);
            cursor = de;
        }
        if (!cursor.isAfter(scope.to())) live.add(new Interval(cursor, scope.to(), true));
        return new Plan(live, days);
    }

    private static boolean touchesDirty(Instant dayStart, Instant dayEnd, Set<LocalDate> dirtyUtc) {
        if (dirtyUtc.isEmpty()) return false;
        LocalDate u = dayStart.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate uEnd = dayEnd.minusNanos(1).atZone(ZoneOffset.UTC).toLocalDate();
        for (; !u.isAfter(uEnd); u = u.plusDays(1)) {
            if (dirtyUtc.contains(u)) return true;
        }
        return false;
    }

    private void runRollup(Scope scope, List<LocalDate> days, Acc acc) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("c", scope.clientId()).addValue("o", scope.orgId())
                .addValue("days", days.stream().map(Date::valueOf).toList());
        String filter = "WHERE client_id = :c AND org_id = :o AND stat_date IN (:days) ";
        if (scope.terminalId() != null) {
            filter += "AND terminal_id = :t ";
            p.addValue("t", scope.terminalId());
        }
        String sql =
                "SELECT kind, k, SUM(cnt) AS cnt, SUM(total) AS total, SUM(qty) AS qty, MAX(prec) AS prec " +
                "FROM order_stats_daily " + filter + "GROUP BY kind, k " +
                "UNION ALL SELECT 'TREND', to_char(stat_date, 'YYYY-MM-DD'), SUM(cnt), SUM(total), NULL, NULL " +
                "FROM order_stats_daily " + filter + "AND kind = 'KPI' GROUP BY stat_date";
        jdbc.query(sql, p, acc.handler());
    }

    private void runLive(Scope scope, List<Interval> intervals, boolean isSale, boolean hourly, Acc acc) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = baseWhere(scope, params, intervals);
        params.addValue("tz", scope.zone());
        params.addValue("dp", scope.decimalPlaces());
        String trendFormat = hourly ? "YYYY-MM-DD\"T\"HH24" : "YYYY-MM-DD";

        StringBuilder sql = new StringBuilder();
        sql.append("WITH f AS MATERIALIZED ( ")
           .append("  SELECT o.id, COALESCE(o.total_amount, 0) AS amt, ")
           .append(STATUS_GROUP).append(" AS sg, ")
           .append(FULFILLMENT_GROUP).append(" AS ff, ")
           .append("  o.warehouse_id, o.vendor_id, ")
           .append("  ((o.order_date AT TIME ZONE 'UTC') AT TIME ZONE :tz) AS lt ")
           .append("  FROM orders o ").append(where)
           .append("), a AS MATERIALIZED (SELECT * FROM f WHERE sg <> 'CANCELLED') ");

        if (isSale) {
            // Latest payment per order decides the method (mirrors Order#paymentMethod @Formula),
            // as a set-based DISTINCT ON join so large ranges can hash-join.
            // MIXED payments are split into CASH / ONLINE using payment_splits; without splits
            // the amount is halved, matching OrderDtoMapper's fallback.
            sql.append(", lp AS ( ")
               .append("  SELECT DISTINCT ON (p.order_id) p.order_id, p.id, p.payment_method ")
               .append("  FROM payments p JOIN a ON a.id = p.order_id ")
               .append("  ORDER BY p.order_id, p.created_at DESC ")
               .append("), pay AS ( ")
               .append("  SELECT a.id, a.amt, lp.id AS pid, ")
               .append("         UPPER(COALESCE(NULLIF(TRIM(lp.payment_method), ''), 'UNPAID')) AS pm ")
               .append("  FROM a LEFT JOIN lp ON lp.order_id = a.id ")
               .append("), mix AS ( ")
               .append("  SELECT pay.id, MAX(pay.amt) AS amt, COUNT(s.id) AS n, ")
               .append("    COALESCE(SUM(CASE WHEN UPPER(s.payment_method) = 'CASH' THEN s.amount END), 0) AS cash, ")
               .append("    COALESCE(SUM(CASE WHEN UPPER(s.payment_method) <> 'CASH' THEN s.amount END), 0) AS online ")
               .append("  FROM pay LEFT JOIN payment_splits s ON s.payment_id = pay.pid ")
               .append("  WHERE pay.pm = 'MIXED' GROUP BY pay.id ")
               .append("), pay_rows AS ( ")
               .append("  SELECT pm AS method, amt AS total FROM pay WHERE pm <> 'MIXED' ")
               .append("  UNION ALL SELECT 'CASH', cash FROM mix WHERE n > 0 AND cash > 0 ")
               .append("  UNION ALL SELECT 'ONLINE', online FROM mix WHERE n > 0 AND online > 0 ")
               .append("  UNION ALL SELECT 'CASH', ROUND(amt / 2, :dp) FROM mix WHERE n = 0 ")
               .append("  UNION ALL SELECT 'ONLINE', amt - ROUND(amt / 2, :dp) FROM mix WHERE n = 0 ")
               .append(") ");
        }

        sql.append("SELECT CAST('KPI' AS text) AS kind, CAST(NULL AS text) AS k, COUNT(*) AS cnt, ")
           .append("       COALESCE(SUM(amt), 0) AS total, CAST(NULL AS numeric) AS qty, CAST(NULL AS integer) AS prec FROM a ")
           .append("UNION ALL SELECT 'TOTAL', NULL, COUNT(*), NULL, NULL, NULL FROM f ")
           .append("UNION ALL SELECT 'STATUS', sg, COUNT(*), NULL, NULL, NULL FROM f GROUP BY sg ")
           .append("UNION ALL SELECT 'HOUR', CAST(CAST(EXTRACT(HOUR FROM lt) AS integer) AS text), COUNT(*), SUM(amt), NULL, NULL FROM a GROUP BY 2 ")
           .append("UNION ALL SELECT 'TREND', to_char(lt, '").append(trendFormat).append("'), COUNT(*), SUM(amt), NULL, NULL FROM a GROUP BY 2 ");

        if (isSale) {
            sql.append("UNION ALL SELECT 'FULFILLMENT', ff, COUNT(*), SUM(amt), NULL, NULL FROM a GROUP BY ff ")
               .append("UNION ALL SELECT 'PAYMENT', method, COUNT(*), SUM(total), NULL, NULL FROM pay_rows GROUP BY method ");
        } else {
            sql.append("UNION ALL (SELECT 'WAREHOUSE', CAST(warehouse_id AS text), COUNT(*), SUM(amt), NULL, NULL FROM a ")
               .append("  WHERE warehouse_id IS NOT NULL GROUP BY warehouse_id ORDER BY 4 DESC LIMIT 10) ")
               .append("UNION ALL (SELECT 'VENDOR', CAST(vendor_id AS text), COUNT(*), SUM(amt), NULL, NULL FROM a ")
               .append("  WHERE vendor_id IS NOT NULL GROUP BY vendor_id ORDER BY 4 DESC LIMIT 10) ");
        }

        // All products (no LIMIT) so live and rolled-up parts can be merged before picking the top 10.
        // COUNT(*) (line count) instead of COUNT(DISTINCT order_id): avoids a full sort that spilled to disk.
        sql.append("UNION ALL SELECT 'ITEM', COALESCE(NULLIF(TRIM(ol.product_name), ''), 'Unknown Item'), ")
           .append("  COUNT(*), COALESCE(SUM(ol.line_total), 0), COALESCE(SUM(ol.quantity), 0), ")
           .append("  MAX(COALESCE(ol.uom_precision, 0)) ")
           .append("  FROM order_lines ol JOIN a ON a.id = ol.order_id WHERE ol.isactive = 'Y' GROUP BY 2");

        jdbc.query(sql.toString(), params, acc.handler());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Paged list for the table view
    // ─────────────────────────────────────────────────────────────────────

    public PageResponse<OrderOverviewRowDto> getOrders(OrderOverviewQuery query) {
        Scope scope = resolveScope(query);
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(
                baseWhere(scope, params, List.of(new Interval(scope.from(), scope.to(), true))));

        String statusGroup = query.getStatus() == null ? "ALL" : query.getStatus().toUpperCase(Locale.ROOT);
        if (!"ALL".equals(statusGroup)) {
            where.append("AND ").append(STATUS_GROUP).append(" = :sg ");
            params.addValue("sg", statusGroup);
        }
        if (query.getQ() != null && !query.getQ().isBlank()) {
            String term = query.getQ().trim().replaceAll("^#+", "").toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            where.append("AND LOWER(o.order_no) LIKE :q ESCAPE '\\' ");
            params.addValue("q", "%" + term + "%");
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM orders o " + where, params, Long.class);
        long totalElements = total == null ? 0 : total;

        int page = query.getEffectivePage();
        int size = query.getEffectiveSize();
        String sortCol = SORT_COLUMNS.getOrDefault(query.getSort() == null ? "date" : query.getSort(), "o.order_date");
        String dir = "asc".equalsIgnoreCase(query.getDir()) ? "ASC" : "DESC";

        String sql = "SELECT o.id, o.order_no, o.order_status, o.order_date, " +
                STATUS_GROUP + " AS sg, COALESCE(o.total_amount, 0) AS amt, " +
                userName("cu", "o.created_by") + " AS created_name, " +
                userName("uu", "o.updated_by") + " AS updated_name " +
                "FROM orders o " +
                "LEFT JOIN users cu ON cu.id = CASE WHEN o.created_by ~ " + UUID_RE + " THEN CAST(o.created_by AS uuid) END " +
                "LEFT JOIN users uu ON uu.id = CASE WHEN o.updated_by ~ " + UUID_RE + " THEN CAST(o.updated_by AS uuid) END " +
                where +
                "ORDER BY " + sortCol + " " + dir + " NULLS LAST, o.id " + dir + " " +
                "LIMIT :limit OFFSET :offset";

        params.addValue("limit", size);
        params.addValue("offset", (long) page * size);

        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        List<OrderOverviewRowDto> rows = jdbc.query(sql, params, (rs, i) -> {
            Timestamp ts = rs.getTimestamp("order_date", utc);
            return OrderOverviewRowDto.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .orderNo(rs.getString("order_no"))
                    .status(rs.getString("sg"))
                    .originalStatus(rs.getString("order_status"))
                    .totalAmount(nz(rs.getBigDecimal("amt")))
                    .createdBy(rs.getString("created_name"))
                    .updatedBy(rs.getString("updated_name"))
                    .orderDate(ts == null ? null : ts.toInstant())
                    .build();
        });

        int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return PageResponse.<OrderOverviewRowDto>builder()
                .content(rows)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .hasNext(page < totalPages - 1)
                .hasPrevious(page > 0)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Display name resolution matching OrderDtoMapper#resolveUserDisplayName. */
    private static String userName(String alias, String column) {
        return "CASE WHEN " + column + " IS NULL OR " + column + " = '' OR UPPER(" + column + ") = 'SYSTEM' THEN 'SYSTEM' " +
               "WHEN " + alias + ".id IS NOT NULL THEN TRIM(COALESCE(" + alias + ".first_name, '') || " +
               "COALESCE(' ' || NULLIF(TRIM(" + alias + ".last_name), ''), '')) " +
               "ELSE " + column + " END";
    }

    private String baseWhere(Scope scope, MapSqlParameterSource params, List<Interval> intervals) {
        StringBuilder where = new StringBuilder("WHERE o.client_id = :clientId AND o.order_type = :orderType ");
        params.addValue("clientId", scope.clientId());
        params.addValue("orderType", scope.orderType());
        if (scope.orgId() != null) {
            where.append("AND o.org_id = :orgId ");
            params.addValue("orgId", scope.orgId());
        }
        if (scope.terminalId() != null) {
            where.append("AND o.terminal_id = :terminalId ");
            params.addValue("terminalId", scope.terminalId());
        }
        where.append("AND ").append(ACTIVE_FILTER).append("AND (");
        for (int i = 0; i < intervals.size(); i++) {
            Interval iv = intervals.get(i);
            if (i > 0) where.append(" OR ");
            where.append("(o.order_date >= :f").append(i)
                 .append(iv.endInclusive() ? " AND o.order_date <= :t" : " AND o.order_date < :t").append(i).append(")");
            params.addValue("f" + i, Timestamp.from(iv.start()));
            params.addValue("t" + i, Timestamp.from(iv.end()));
        }
        return where.append(") ").toString();
    }

    private Scope resolveScope(OrderOverviewQuery query) {
        if (query.getFrom() == null || query.getTo() == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (query.getFrom().isAfter(query.getTo())) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(query.getFrom(), query.getTo()).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("Date range cannot exceed " + MAX_RANGE.toDays() + " days");
        }

        String zone = query.getTz() == null || query.getTz().isBlank() ? "UTC" : query.getTz().trim();
        try {
            // Region IDs only: Java and PostgreSQL disagree on the sign of raw "+05:30"-style offsets.
            if (!TZ_NAME.matcher(zone).matches()) throw new DateTimeException(zone);
            zone = OrderStatsRollupService.canonicalZone(ZoneId.of(zone).getId());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid timezone: " + query.getTz());
        }

        // The date picker works in minutes, so "to" arrives as 23:59:00. Treat that as the end of
        // the day; otherwise orders in the last minute of the day would be left out.
        Instant to = query.getTo();
        LocalTime toLocal = to.atZone(ZoneId.of(zone)).toLocalTime();
        if (toLocal.equals(LocalTime.of(23, 59))) {
            to = to.plusSeconds(60).minusNanos(1000);
        }

        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = SecurityUtils.isSuperAdmin()
                ? (query.getOrgId() != null ? query.getOrgId() : branchContext.getReadOrgId(null))
                : TenantContext.getCurrentOrg();

        if (query.getTerminalId() != null) {
            if (orgId != null) {
                terminalRepository.findByIdAndClientIdAndOrgId(query.getTerminalId(), clientId, orgId)
                        .orElseThrow(() -> new BusinessException("Terminal not found or does not belong to the active branch"));
            } else {
                terminalRepository.findByIdAndClientId(query.getTerminalId(), clientId)
                        .orElseThrow(() -> new BusinessException("Terminal not found"));
            }
        }

        String type = query.getType() == null ? "SALE" : query.getType().toUpperCase(Locale.ROOT);
        int dp = currencyScale.decimalPlaces(clientId, orgId);
        return new Scope(clientId, orgId, query.getTerminalId(), type, query.getFrom(), to, zone, dp);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private record Scope(UUID clientId, UUID orgId, UUID terminalId, String orderType,
                         Instant from, Instant to, String zone, int decimalPlaces) {}

    private record Interval(Instant start, Instant end, boolean endInclusive) {}

    private record Plan(List<Interval> live, List<LocalDate> days) {}

    /** Accumulates metric rows from the live query and the rollup query into one response. */
    private static final class Acc {
        private long orderCount;
        private long totalCount;
        private BigDecimal revenue = BigDecimal.ZERO;
        private final Map<String, Long> status = new LinkedHashMap<>();
        private final Map<String, Bucket> hour = new HashMap<>();
        private final Map<String, Bucket> trend = new HashMap<>();
        private final Map<String, Bucket> fulfillment = new HashMap<>();
        private final Map<String, Bucket> payment = new HashMap<>();
        private final Map<String, Bucket> warehouse = new HashMap<>();
        private final Map<String, Bucket> vendor = new HashMap<>();
        private final Map<String, ItemBucket> items = new HashMap<>();

        Acc() {
            for (String s : List.of("ORDERED", "BILLED", "COMPLETED", "CANCELLED")) status.put(s, 0L);
        }

        RowCallbackHandler handler() {
            return rs -> {
                String kind = rs.getString("kind");
                String key = rs.getString("k");
                long cnt = rs.getLong("cnt");
                BigDecimal total = nz(rs.getBigDecimal("total"));
                switch (kind) {
                    case "KPI" -> { orderCount += cnt; revenue = revenue.add(total); }
                    case "TOTAL" -> totalCount += cnt;
                    case "STATUS" -> status.merge(key, cnt, Long::sum);
                    case "HOUR" -> add(hour, key, cnt, total);
                    case "TREND" -> add(trend, key, cnt, total);
                    case "FULFILLMENT" -> add(fulfillment, key, cnt, total);
                    case "PAYMENT" -> add(payment, key, cnt, total);
                    case "WAREHOUSE" -> add(warehouse, key, cnt, total);
                    case "VENDOR" -> add(vendor, key, cnt, total);
                    case "ITEM" -> {
                        BigDecimal qty = nz(rs.getBigDecimal("qty"));
                        int prec = rs.getInt("prec");
                        ItemBucket it = items.get(key);
                        if (it == null) {
                            items.put(key, ItemBucket.builder().name(key).orderCount(cnt).total(total)
                                    .quantity(qty).uomPrecision(prec).build());
                        } else {
                            it.setOrderCount(it.getOrderCount() + cnt);
                            it.setTotal(it.getTotal().add(total));
                            it.setQuantity(it.getQuantity().add(qty));
                            it.setUomPrecision(Math.max(it.getUomPrecision(), prec));
                        }
                    }
                    default -> { }
                }
            };
        }

        private static void add(Map<String, Bucket> map, String key, long cnt, BigDecimal total) {
            Bucket b = map.get(key);
            if (b == null) map.put(key, new Bucket(key, cnt, total));
            else { b.setCount(b.getCount() + cnt); b.setTotal(b.getTotal().add(total)); }
        }

        OrderOverviewResponse toResponse(int dp, boolean hourly) {
            Comparator<Bucket> byTotalDesc = Comparator.comparing(Bucket::getTotal).reversed();
            List<Bucket> hours = new ArrayList<>(24);
            for (int h = 0; h < 24; h++) {
                hours.add(hour.getOrDefault(String.valueOf(h), new Bucket(String.valueOf(h), 0, BigDecimal.ZERO)));
            }
            BigDecimal avg = orderCount > 0
                    ? revenue.divide(BigDecimal.valueOf(orderCount), dp, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            return OrderOverviewResponse.builder()
                    .trendGranularity(hourly ? "HOUR" : "DAY")
                    .orderCount(orderCount)
                    .totalCount(totalCount)
                    .revenue(revenue)
                    .avgOrderValue(avg)
                    .byStatus(status)
                    .byFulfillment(sorted(fulfillment, byTotalDesc, Integer.MAX_VALUE))
                    .byPayment(sorted(payment, byTotalDesc, Integer.MAX_VALUE))
                    .byWarehouse(sorted(warehouse, byTotalDesc, 10))
                    .byVendor(sorted(vendor, byTotalDesc, 10))
                    .hourly(hours)
                    .trend(sorted(trend, Comparator.comparing(Bucket::getKey), Integer.MAX_VALUE))
                    .topItems(items.values().stream()
                            .sorted(Comparator.comparing(ItemBucket::getQuantity).reversed()
                                    .thenComparing(ItemBucket::getName))
                            .limit(10).toList())
                    .build();
        }

        private static List<Bucket> sorted(Map<String, Bucket> map, Comparator<Bucket> cmp, int limit) {
            return map.values().stream().sorted(cmp).limit(limit).toList();
        }
    }
}
