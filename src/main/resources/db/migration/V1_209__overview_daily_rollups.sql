-- V1_209: Pre-aggregated daily totals for the Business Overview screen (SALE orders).
--
-- order_stats_daily  : one row per (branch, branch-local day, terminal, metric kind, key)
-- order_stats_day    : which branch-local days are computed, and in which timezone
-- order_stats_dirty  : append-only change log written by triggers; a day with a pending
--                      change is read live from orders until the job recomputes it, so the
--                      Overview is never stale.
--
-- OrderStatsRollupService (scheduled) drains the change log and backfills history.

CREATE TABLE IF NOT EXISTS order_stats_daily (
    client_id   UUID          NOT NULL,
    org_id      UUID          NOT NULL,
    stat_date   DATE          NOT NULL,
    terminal_id UUID,
    kind        VARCHAR(16)   NOT NULL,
    k           TEXT,
    cnt         BIGINT        NOT NULL DEFAULT 0,
    total       NUMERIC(18,2) NOT NULL DEFAULT 0,
    qty         NUMERIC(18,4),
    prec        INTEGER
);
CREATE INDEX IF NOT EXISTS idx_order_stats_daily_scope
    ON order_stats_daily (client_id, org_id, stat_date)
    INCLUDE (terminal_id, kind, k, cnt, total, qty, prec);

CREATE TABLE IF NOT EXISTS order_stats_day (
    client_id   UUID        NOT NULL,
    org_id      UUID        NOT NULL,
    stat_date   DATE        NOT NULL,
    tz          VARCHAR(64) NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (client_id, org_id, stat_date)
);

CREATE TABLE IF NOT EXISTS order_stats_dirty (
    id         BIGSERIAL   PRIMARY KEY,
    client_id  UUID        NOT NULL,
    org_id     UUID        NOT NULL,
    utc_date   DATE        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_order_stats_dirty_scope ON order_stats_dirty (client_id, org_id, utc_date);

-- Logs one change per (branch, UTC date) per transaction; plain INSERT (no upsert) so
-- concurrent POS transactions never contend on a shared row.
CREATE OR REPLACE FUNCTION order_stats_mark_dirty(p_client UUID, p_org UUID, p_type TEXT, p_date TIMESTAMP)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    v_key TEXT;
BEGIN
    IF p_type IS DISTINCT FROM 'SALE' OR p_client IS NULL OR p_org IS NULL OR p_date IS NULL THEN
        RETURN;
    END IF;
    v_key := 'cafeqr.osd_' || md5(p_org::text || ':' || p_date::date::text);
    IF current_setting(v_key, true) = '1' THEN
        RETURN;
    END IF;
    INSERT INTO order_stats_dirty (client_id, org_id, utc_date) VALUES (p_client, p_org, p_date::date);
    PERFORM set_config(v_key, '1', true);
END $$;

CREATE OR REPLACE FUNCTION trg_orders_stats_dirty() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM order_stats_mark_dirty(NEW.client_id, NEW.org_id, NEW.order_type, NEW.order_date);
    END IF;
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        PERFORM order_stats_mark_dirty(OLD.client_id, OLD.org_id, OLD.order_type, OLD.order_date);
    END IF;
    RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION trg_order_child_stats_dirty() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_new UUID;
    v_old UUID;
BEGIN
    IF TG_TABLE_NAME = 'payment_splits' THEN
        IF TG_OP <> 'DELETE' THEN SELECT p.order_id INTO v_new FROM payments p WHERE p.id = NEW.payment_id; END IF;
        IF TG_OP <> 'INSERT' THEN SELECT p.order_id INTO v_old FROM payments p WHERE p.id = OLD.payment_id; END IF;
    ELSE
        IF TG_OP <> 'DELETE' THEN v_new := NEW.order_id; END IF;
        IF TG_OP <> 'INSERT' THEN v_old := OLD.order_id; END IF;
    END IF;
    IF v_new IS NOT NULL THEN
        PERFORM order_stats_mark_dirty(o.client_id, o.org_id, o.order_type, o.order_date) FROM orders o WHERE o.id = v_new;
    END IF;
    IF v_old IS NOT NULL AND v_old IS DISTINCT FROM v_new THEN
        PERFORM order_stats_mark_dirty(o.client_id, o.org_id, o.order_type, o.order_date) FROM orders o WHERE o.id = v_old;
    END IF;
    RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS trg_orders_stats_dirty ON orders;
CREATE TRIGGER trg_orders_stats_dirty AFTER INSERT OR UPDATE OR DELETE ON orders
    FOR EACH ROW EXECUTE FUNCTION trg_orders_stats_dirty();

DROP TRIGGER IF EXISTS trg_order_lines_stats_dirty ON order_lines;
CREATE TRIGGER trg_order_lines_stats_dirty AFTER INSERT OR UPDATE OR DELETE ON order_lines
    FOR EACH ROW EXECUTE FUNCTION trg_order_child_stats_dirty();

DROP TRIGGER IF EXISTS trg_payments_stats_dirty ON payments;
CREATE TRIGGER trg_payments_stats_dirty AFTER INSERT OR UPDATE OR DELETE ON payments
    FOR EACH ROW EXECUTE FUNCTION trg_order_child_stats_dirty();

DROP TRIGGER IF EXISTS trg_payment_splits_stats_dirty ON payment_splits;
CREATE TRIGGER trg_payment_splits_stats_dirty AFTER INSERT OR UPDATE OR DELETE ON payment_splits
    FOR EACH ROW EXECUTE FUNCTION trg_order_child_stats_dirty();
