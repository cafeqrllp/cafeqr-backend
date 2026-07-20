-- V1_100: Add composite and partial indexes for report performance optimization
CREATE INDEX IF NOT EXISTS idx_orders_report_composite ON orders(client_id, org_id, order_type, order_status, order_date DESC) WHERE isactive = 'Y';
CREATE INDEX IF NOT EXISTS idx_payments_order ON payments(order_id) WHERE isactive = 'Y';
CREATE INDEX IF NOT EXISTS idx_invoices_order ON invoices(order_id) WHERE isactive = 'Y';
