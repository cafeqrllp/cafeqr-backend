-- Migration script to add database indexes for Sales Dashboard & Sales Query performance optimization

CREATE INDEX IF NOT EXISTS idx_orders_client_org_created
    ON orders(client_id, org_id, created_at);

CREATE INDEX IF NOT EXISTS idx_orders_client_org_type_status
    ON orders(client_id, org_id, order_type, order_status);

CREATE INDEX IF NOT EXISTS idx_order_lines_order_id_active
    ON order_lines(order_id, isactive);

CREATE INDEX IF NOT EXISTS idx_payments_order_id_created
    ON payments(order_id, created_at DESC);
