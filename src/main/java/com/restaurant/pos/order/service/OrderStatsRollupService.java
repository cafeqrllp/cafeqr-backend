package com.restaurant.pos.order.service;

import com.restaurant.pos.common.service.CurrencyScaleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Maintains the pre-aggregated daily totals ({@code order_stats_daily}) behind the
 * Business Overview screen.
 *
 * <ul>
 *   <li>Triggers append to {@code order_stats_dirty} whenever a SALE order, line, payment
 *       or split changes. Until this job recomputes the affected day, the Overview reads
 *       that day live from {@code orders}, so results are never stale.</li>
 *   <li>Each run drains the change log, then backfills missing history newest-first.</li>
 *   <li>Each day is recomputed in its own short transaction under an advisory lock, so
 *       several backend instances can run the job safely.</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderStatsRollupService {

    private static final int DIRTY_BATCH = 5000;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CurrencyScaleService currencyScale;

    @Value("${overview.rollup.enabled:true}")
    private boolean enabled;

    @Value("${overview.rollup.backfill-days-per-run:120}")
    private int backfillBudget;

    public OrderStatsRollupService(NamedParameterJdbcTemplate jdbc,
                                   PlatformTransactionManager transactionManager,
                                   CurrencyScaleService currencyScale) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setTimeout(60);
        this.currencyScale = currencyScale;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Same aliases the browser may report; rollups are keyed by the canonical name. */
    private static final Map<String, String> TZ_ALIASES = Map.of(
            "Asia/Calcutta", "Asia/Kolkata",
            "Asia/Katmandu", "Asia/Kathmandu",
            "Asia/Saigon", "Asia/Ho_Chi_Minh",
            "Asia/Rangoon", "Asia/Yangon");

    static String canonicalZone(String zone) {
        return TZ_ALIASES.getOrDefault(zone, zone);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Scheduled job
    // ─────────────────────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${overview.rollup.interval-ms:30000}",
               initialDelayString = "${overview.rollup.initial-delay-ms:60000}")
    public void run() {
        if (!enabled) return;
        try {
            Map<UUID, Branch> branches = loadBranches();
            int recomputed = processDirty(branches);
            int backfilled = backfill(branches);
            if (recomputed + backfilled > 0) {
                log.debug("Overview rollups: {} day(s) refreshed, {} day(s) backfilled", recomputed, backfilled);
            }
        } catch (Exception e) {
            log.warn("Overview rollup run failed: {}", e.getMessage(), e);
        }
    }

    /** Branch timezone resolved like the frontend: branch zone, else client zone, 'UTC' meaning unset. */
    private Map<UUID, Branch> loadBranches() {
        Map<UUID, Branch> out = new HashMap<>();
        jdbc.query("SELECT o.client_id, o.id, " +
                   "COALESCE(NULLIF(NULLIF(o.timezone, ''), 'UTC'), NULLIF(NULLIF(c.timezone, ''), 'UTC'), 'UTC') AS tz " +
                   "FROM organizations o JOIN clients c ON c.id = o.client_id",
                new MapSqlParameterSource(), rs -> {
                    UUID org = UUID.fromString(rs.getString("id"));
                    String zone = canonicalZone(rs.getString("tz"));
                    try {
                        ZoneId.of(zone);
                    } catch (DateTimeException e) {
                        zone = "UTC";
                    }
                    out.put(org, new Branch(UUID.fromString(rs.getString("client_id")), org, zone));
                });
        return out;
    }

    private int processDirty(Map<UUID, Branch> branches) {
        Map<UUID, List<Long>> idsByOrg = new HashMap<>();
        Map<UUID, Set<LocalDate>> utcDatesByOrg = new HashMap<>();
        jdbc.query("SELECT id, org_id, utc_date FROM order_stats_dirty ORDER BY id LIMIT " + DIRTY_BATCH,
                new MapSqlParameterSource(), rs -> {
                    UUID org = UUID.fromString(rs.getString("org_id"));
                    idsByOrg.computeIfAbsent(org, k -> new ArrayList<>()).add(rs.getLong("id"));
                    utcDatesByOrg.computeIfAbsent(org, k -> new TreeSet<>()).add(rs.getDate("utc_date").toLocalDate());
                });
        if (idsByOrg.isEmpty()) return 0;

        int days = 0;
        // Only the log rows we actually read are deleted, and only for branches refreshed in full.
        // Rows from transactions still open when we read the log stay and are handled next run.
        List<Long> done = new ArrayList<>();
        for (Map.Entry<UUID, Set<LocalDate>> e : utcDatesByOrg.entrySet()) {
            Branch b = branches.get(e.getKey());
            if (b == null) { // branch deleted: nothing to refresh
                done.addAll(idsByOrg.get(e.getKey()));
                continue;
            }
            ZoneId zone = ZoneId.of(b.zone());
            Set<LocalDate> localDays = new TreeSet<>();
            for (LocalDate utc : e.getValue()) {
                localDays.add(utc.atStartOfDay(ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDate());
                localDays.add(utc.plusDays(1).atStartOfDay(ZoneOffset.UTC).minusNanos(1).withZoneSameInstant(zone).toLocalDate());
            }
            try {
                boolean all = true;
                for (LocalDate d : localDays) {
                    if (recomputeDay(b, d)) days++;
                    else all = false; // another instance holds the lock: retry next run
                }
                if (all) done.addAll(idsByOrg.get(e.getKey()));
            } catch (Exception ex) {
                log.warn("Overview rollup refresh failed for branch {}: {}", b.org(), ex.getMessage());
            }
        }
        for (int i = 0; i < done.size(); i += 1000) {
            jdbc.update("DELETE FROM order_stats_dirty WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", done.subList(i, Math.min(done.size(), i + 1000))));
        }
        return days;
    }

    private int backfill(Map<UUID, Branch> branches) {
        int budget = backfillBudget;
        for (Branch b : branches.values()) {
            if (budget <= 0) break;
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("c", b.client()).addValue("o", b.org());
            Timestamp min = jdbc.queryForObject(
                    "SELECT MIN(order_date) FROM orders WHERE client_id = :c AND org_id = :o AND order_type = 'SALE'",
                    p, Timestamp.class);
            if (min == null) continue;
            ZoneId zone = ZoneId.of(b.zone());
            LocalDate first = min.toInstant().atZone(zone).toLocalDate();
            LocalDate today = LocalDate.now(zone);
            p.addValue("first", Date.valueOf(first)).addValue("today", Date.valueOf(today))
             .addValue("tz", b.zone()).addValue("lim", budget);
            List<LocalDate> missing = jdbc.query(
                    "SELECT d::date AS d FROM generate_series(CAST(:first AS date), CAST(:today AS date), interval '1 day') d " +
                    "WHERE NOT EXISTS (SELECT 1 FROM order_stats_day s WHERE s.client_id = :c AND s.org_id = :o " +
                    "  AND s.stat_date = d::date AND s.tz = :tz) " +
                    "ORDER BY d DESC LIMIT :lim",
                    p, (rs, i) -> rs.getDate("d").toLocalDate());
            for (LocalDate d : missing) {
                if (recomputeDay(b, d)) budget--;
            }
        }
        return backfillBudget - budget;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Recompute one branch-local day
    // ─────────────────────────────────────────────────────────────────────

    /** @return false if another instance holds the lock for this day. */
    boolean recomputeDay(Branch b, LocalDate day) {
        ZoneId zone = ZoneId.of(b.zone());
        Instant start = day.atStartOfDay(zone).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
        int dp = currencyScale.decimalPlaces(b.client(), b.org());

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("c", b.client()).addValue("o", b.org())
                .addValue("d", Date.valueOf(day)).addValue("tz", b.zone()).addValue("dp", dp)
                .addValue("s", Timestamp.from(start)).addValue("e", Timestamp.from(end))
                .addValue("lock", b.org() + ":" + day);

        Boolean ok = tx.execute(status -> {
            Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtext(:lock))", p, Boolean.class);
            if (!Boolean.TRUE.equals(locked)) return false;
            jdbc.getJdbcTemplate().execute("SET LOCAL work_mem = '64MB'");
            jdbc.update("DELETE FROM order_stats_daily WHERE client_id = :c AND org_id = :o AND stat_date = :d", p);
            jdbc.update(RECOMPUTE_SQL, p);
            jdbc.update("INSERT INTO order_stats_day (client_id, org_id, stat_date, tz, computed_at) " +
                        "VALUES (:c, :o, :d, :tz, now()) " +
                        "ON CONFLICT (client_id, org_id, stat_date) DO UPDATE SET tz = EXCLUDED.tz, computed_at = now()", p);
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    /**
     * Same metrics and rules as the live Overview query, grouped per terminal so the
     * terminal filter keeps working on rollups.
     */
    private static final String RECOMPUTE_SQL =
            "WITH f AS MATERIALIZED ( " +
            "  SELECT o.id, o.terminal_id, COALESCE(o.total_amount, 0) AS amt, " +
            OrderOverviewService.STATUS_GROUP + " AS sg, " +
            OrderOverviewService.FULFILLMENT_GROUP + " AS ff, " +
            "  ((o.order_date AT TIME ZONE 'UTC') AT TIME ZONE :tz) AS lt " +
            "  FROM orders o " +
            "  WHERE o.client_id = :c AND o.org_id = :o AND o.order_type = 'SALE' " +
            "  AND " + OrderOverviewService.ACTIVE_FILTER +
            "  AND o.order_date >= :s AND o.order_date < :e " +
            "), a AS MATERIALIZED (SELECT * FROM f WHERE sg <> 'CANCELLED') " +
            ", lp AS ( " +
            "  SELECT DISTINCT ON (p.order_id) p.order_id, p.id, p.payment_method " +
            "  FROM payments p JOIN a ON a.id = p.order_id ORDER BY p.order_id, p.created_at DESC " +
            "), pay AS ( " +
            "  SELECT a.id, a.terminal_id, a.amt, lp.id AS pid, " +
            "         UPPER(COALESCE(NULLIF(TRIM(lp.payment_method), ''), 'UNPAID')) AS pm " +
            "  FROM a LEFT JOIN lp ON lp.order_id = a.id " +
            "), mix AS ( " +
            "  SELECT pay.id, pay.terminal_id, MAX(pay.amt) AS amt, COUNT(s.id) AS n, " +
            "    COALESCE(SUM(CASE WHEN UPPER(s.payment_method) = 'CASH' THEN s.amount END), 0) AS cash, " +
            "    COALESCE(SUM(CASE WHEN UPPER(s.payment_method) <> 'CASH' THEN s.amount END), 0) AS online " +
            "  FROM pay LEFT JOIN payment_splits s ON s.payment_id = pay.pid " +
            "  WHERE pay.pm = 'MIXED' GROUP BY pay.id, pay.terminal_id " +
            "), pay_rows AS ( " +
            "  SELECT terminal_id, pm AS method, amt AS total FROM pay WHERE pm <> 'MIXED' " +
            "  UNION ALL SELECT terminal_id, 'CASH', cash FROM mix WHERE n > 0 AND cash > 0 " +
            "  UNION ALL SELECT terminal_id, 'ONLINE', online FROM mix WHERE n > 0 AND online > 0 " +
            "  UNION ALL SELECT terminal_id, 'CASH', ROUND(amt / 2, :dp) FROM mix WHERE n = 0 " +
            "  UNION ALL SELECT terminal_id, 'ONLINE', amt - ROUND(amt / 2, :dp) FROM mix WHERE n = 0 " +
            ") " +
            "INSERT INTO order_stats_daily (client_id, org_id, stat_date, terminal_id, kind, k, cnt, total, qty, prec) " +
            "SELECT :c, :o, :d, x.terminal_id, x.kind, x.k, x.cnt, x.total, x.qty, x.prec FROM ( " +
            "  SELECT terminal_id, CAST('KPI' AS varchar) AS kind, CAST(NULL AS text) AS k, COUNT(*) AS cnt, " +
            "         COALESCE(SUM(amt), 0) AS total, CAST(NULL AS numeric) AS qty, CAST(NULL AS integer) AS prec " +
            "  FROM a GROUP BY terminal_id " +
            "  UNION ALL SELECT terminal_id, 'TOTAL', NULL, COUNT(*), 0, NULL, NULL FROM f GROUP BY terminal_id " +
            "  UNION ALL SELECT terminal_id, 'STATUS', sg, COUNT(*), 0, NULL, NULL FROM f GROUP BY terminal_id, sg " +
            "  UNION ALL SELECT terminal_id, 'HOUR', CAST(CAST(EXTRACT(HOUR FROM lt) AS integer) AS text), COUNT(*), SUM(amt), NULL, NULL " +
            "    FROM a GROUP BY 1, 3 " +
            "  UNION ALL SELECT terminal_id, 'FULFILLMENT', ff, COUNT(*), SUM(amt), NULL, NULL FROM a GROUP BY terminal_id, ff " +
            "  UNION ALL SELECT terminal_id, 'PAYMENT', method, COUNT(*), SUM(total), NULL, NULL FROM pay_rows GROUP BY terminal_id, method " +
            "  UNION ALL SELECT a.terminal_id, 'ITEM', COALESCE(NULLIF(TRIM(ol.product_name), ''), 'Unknown Item'), COUNT(*), " +
            "    COALESCE(SUM(ol.line_total), 0), COALESCE(SUM(ol.quantity), 0), MAX(COALESCE(ol.uom_precision, 0)) " +
            "    FROM order_lines ol JOIN a ON a.id = ol.order_id WHERE ol.isactive = 'Y' GROUP BY 1, 3 " +
            ") x";

    record Branch(UUID client, UUID org, String zone) {}
}
