-- ===========================================================================
-- V1_170: POS Sales V2 Database Performance Optimizations
--
-- 1. Denormalized item_count on orders (eliminates correlated COUNT subquery)
-- 2. Denormalized current_balance on credit_customers (eliminates invoice SUM)
-- 3. Covering indexes for lateral payment/invoice lookups in sales history
-- 4. Partial indexes for live order incremental polling
-- 5. PostgreSQL trigram indexes for ILIKE '%term%' acceleration
-- ===========================================================================

-- ═══════════════════════════════════════════════════════════════════════════
-- 1. orders.item_count — denormalized active order-line count
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE orders ADD COLUMN IF NOT EXISTS item_count INT NOT NULL DEFAULT 0;

-- Backfill from existing order_lines
UPDATE orders o
SET item_count = COALESCE((
    SELECT COUNT(*)
    FROM order_lines ol
    WHERE ol.order_id = o.id
      AND ol.isactive = 'Y'
), 0)
WHERE EXISTS (
    SELECT 1 FROM order_lines ol WHERE ol.order_id = o.id
);

-- Trigger function: maintain item_count on order_lines INSERT/UPDATE/DELETE
CREATE OR REPLACE FUNCTION fn_order_lines_item_count()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.isactive = 'Y' THEN
            UPDATE orders SET item_count = item_count + 1 WHERE id = NEW.order_id;
        END IF;
        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN
        IF OLD.isactive = 'Y' THEN
            UPDATE orders SET item_count = GREATEST(item_count - 1, 0) WHERE id = OLD.order_id;
        END IF;
        RETURN OLD;

    ELSIF TG_OP = 'UPDATE' THEN
        -- Case 1: line deactivated (Y -> N)
        IF OLD.isactive = 'Y' AND NEW.isactive <> 'Y' THEN
            UPDATE orders SET item_count = GREATEST(item_count - 1, 0) WHERE id = NEW.order_id;
        -- Case 2: line reactivated (N -> Y)
        ELSIF OLD.isactive <> 'Y' AND NEW.isactive = 'Y' THEN
            UPDATE orders SET item_count = item_count + 1 WHERE id = NEW.order_id;
        -- Case 3: line moved to different order
        ELSIF OLD.order_id <> NEW.order_id THEN
            IF OLD.isactive = 'Y' THEN
                UPDATE orders SET item_count = GREATEST(item_count - 1, 0) WHERE id = OLD.order_id;
            END IF;
            IF NEW.isactive = 'Y' THEN
                UPDATE orders SET item_count = item_count + 1 WHERE id = NEW.order_id;
            END IF;
        END IF;
        RETURN NEW;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_order_lines_item_count ON order_lines;
CREATE TRIGGER trg_order_lines_item_count
    AFTER INSERT OR UPDATE OR DELETE ON order_lines
    FOR EACH ROW EXECUTE FUNCTION fn_order_lines_item_count();


-- ═══════════════════════════════════════════════════════════════════════════
-- 2. credit_customers.current_balance — denormalized outstanding balance
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE credit_customers ADD COLUMN IF NOT EXISTS current_balance NUMERIC(15, 2) NOT NULL DEFAULT 0.00;

-- Backfill: opening_balance + SUM(active non-void invoice amount_due)
UPDATE credit_customers cc
SET current_balance = COALESCE(cc.opening_balance, 0) + COALESCE((
    SELECT SUM(i.amount_due)
    FROM invoices i
    WHERE i.client_id = cc.client_id
      AND i.credit_customer_id = cc.id
      AND i.isactive = 'Y'
      AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
), 0);

-- Trigger function: maintain current_balance on invoices INSERT/UPDATE/DELETE
CREATE OR REPLACE FUNCTION fn_invoices_credit_balance()
RETURNS TRIGGER AS $$
DECLARE
    v_customer_id UUID;
    v_client_id   UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_customer_id := OLD.credit_customer_id;
        v_client_id   := OLD.client_id;
    ELSE
        v_customer_id := NEW.credit_customer_id;
        v_client_id   := NEW.client_id;
    END IF;

    -- Only act on invoices linked to a credit customer
    IF v_customer_id IS NULL THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    END IF;

    -- Full recalculation for the affected credit customer
    -- This is safe because the trigger fires per-row and credit_customer scope is narrow
    UPDATE credit_customers cc
    SET current_balance = COALESCE(cc.opening_balance, 0) + COALESCE((
        SELECT SUM(i.amount_due)
        FROM invoices i
        WHERE i.client_id = cc.client_id
          AND i.credit_customer_id = cc.id
          AND i.isactive = 'Y'
          AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
    ), 0)
    WHERE cc.id = v_customer_id
      AND cc.client_id = v_client_id;

    -- Handle UPDATE where credit_customer_id changed (old customer also needs recalc)
    IF TG_OP = 'UPDATE' AND OLD.credit_customer_id IS DISTINCT FROM NEW.credit_customer_id AND OLD.credit_customer_id IS NOT NULL THEN
        UPDATE credit_customers cc
        SET current_balance = COALESCE(cc.opening_balance, 0) + COALESCE((
            SELECT SUM(i.amount_due)
            FROM invoices i
            WHERE i.client_id = cc.client_id
              AND i.credit_customer_id = cc.id
              AND i.isactive = 'Y'
              AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
        ), 0)
        WHERE cc.id = OLD.credit_customer_id
          AND cc.client_id = OLD.client_id;
    END IF;

    IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_invoices_credit_balance ON invoices;
CREATE TRIGGER trg_invoices_credit_balance
    AFTER INSERT OR UPDATE OR DELETE ON invoices
    FOR EACH ROW EXECUTE FUNCTION fn_invoices_credit_balance();


-- ═══════════════════════════════════════════════════════════════════════════
-- 3. Covering indexes for lateral payment/invoice lookups (sales history)
-- ═══════════════════════════════════════════════════════════════════════════

CREATE INDEX IF NOT EXISTS idx_payments_pos_lateral_latest
ON payments (order_id, created_at DESC)
INCLUDE (payment_method, reference_no)
WHERE isactive = 'Y';

CREATE INDEX IF NOT EXISTS idx_invoices_pos_lateral_latest
ON invoices (order_id, created_at DESC)
INCLUDE (invoice_no, daily_bill_no);


-- ═══════════════════════════════════════════════════════════════════════════
-- 4. Partial indexes for live order incremental polling
-- ═══════════════════════════════════════════════════════════════════════════

-- Incremental polling: live orders updated after cursor
CREATE INDEX IF NOT EXISTS idx_orders_pos_live_incremental
ON orders (client_id, org_id, updated_at ASC, id ASC)
WHERE order_type = 'SALE'
  AND isactive = 'Y'
  AND order_status NOT IN ('COMPLETED', 'CANCELLED', 'VOID');

-- Terminal status sync: orders that moved to terminal state
CREATE INDEX IF NOT EXISTS idx_orders_pos_live_terminal_sync
ON orders (client_id, org_id, updated_at ASC, id ASC)
WHERE order_type = 'SALE'
  AND isactive = 'Y'
  AND order_status IN ('COMPLETED', 'CANCELLED', 'VOID');

-- Initial live board load: newest first
CREATE INDEX IF NOT EXISTS idx_orders_pos_live_initial
ON orders (client_id, org_id, order_date DESC, created_at DESC)
WHERE order_type = 'SALE'
  AND isactive = 'Y'
  AND order_status NOT IN ('COMPLETED', 'CANCELLED', 'VOID');


-- ═══════════════════════════════════════════════════════════════════════════
-- 5. PostgreSQL trigram indexes for ILIKE '%term%' acceleration
-- ═══════════════════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_products_name_trgm
ON products USING gin (name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_products_product_code_trgm
ON products USING gin (product_code gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_customers_name_trgm
ON customers USING gin (name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_customers_phone_trgm
ON customers USING gin (phone gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_credit_customers_name_trgm
ON credit_customers USING gin (name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_credit_customers_phone_trgm
ON credit_customers USING gin (phone gin_trgm_ops);
