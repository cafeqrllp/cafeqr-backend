-- ===========================================================================
-- V1_165: Performance indexes for POS Sales History and Bootstrap queries
-- ===========================================================================

-- 1. Covering index for Sales History: avoids heap-fetch for common projection columns
CREATE INDEX IF NOT EXISTS idx_orders_pos_history_perf
ON orders (client_id, org_id, order_type, order_date DESC, created_at DESC)
INCLUDE (id, order_no, grand_total, total_amount, total_tax_amount, total_discount_amount,
         gross_amount, order_status, payment_status, customer_id, fulfillment_type,
         table_id, table_number, is_credit, credit_customer_id);

-- 2. Covering index for payment lookup by order (eliminates @Formula subqueries)
CREATE INDEX IF NOT EXISTS idx_payments_order_cover
ON payments (order_id, created_at DESC)
INCLUDE (payment_method, reference_no, amount_paid, round_off_amount);

-- 3. Fast product delta sync for bootstrap (sorted by updated_at for incremental sync with org-level isolation)
CREATE INDEX IF NOT EXISTS idx_products_sync_delta
ON products (client_id, org_id, updated_at DESC, is_active);

-- 4. Order line count acceleration (avoids full scan for itemCount projection)
CREATE INDEX IF NOT EXISTS idx_order_lines_order_active
ON order_lines (order_id, isactive);
