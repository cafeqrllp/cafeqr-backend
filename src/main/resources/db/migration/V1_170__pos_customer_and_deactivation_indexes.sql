-- Flyway Migration V1_167: Indexes for POS customer quick search and delta deactivation sync
-- Ensures sub-millisecond execution for POS Customer quick lookup and delta sync with org-level isolation

CREATE INDEX IF NOT EXISTS idx_customers_pos_search
ON customers (client_id, org_id, isactive, name, phone);

CREATE INDEX IF NOT EXISTS idx_products_deactivated_sync
ON products (client_id, org_id, is_active, updated_at)
WHERE is_active = false;
