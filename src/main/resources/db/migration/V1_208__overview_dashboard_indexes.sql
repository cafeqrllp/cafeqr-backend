-- V1_208: Indexes for the Business Overview screen (/api/v1/orders/overview/*).
-- Branch-scoped queries are already served by idx_orders_pos_history_perf
-- (client_id, org_id, order_type, order_date). These cover the remaining paths.

-- "All Branches" (super admin, org_id unscoped) date-range scans across every status.
CREATE INDEX IF NOT EXISTS idx_orders_overview_all_branches
    ON orders (client_id, order_type, order_date DESC)
    INCLUDE (id, org_id, total_amount, order_status, fulfillment_type, table_number, warehouse_id, vendor_id)
    WHERE isactive = 'Y';

-- MIXED payment split lookup by payment_id (existing index leads with client_id).
CREATE INDEX IF NOT EXISTS idx_payment_splits_payment_cover
    ON payment_splits (payment_id)
    INCLUDE (payment_method, amount);
