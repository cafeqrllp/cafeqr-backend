-- V1_187: Add performance index on orders for tenant-wide multi-branch report queries where org_id is null.
-- Prevents full table scan on orders during sales summary and report generation.
CREATE INDEX IF NOT EXISTS idx_orders_report_no_org 
ON orders(client_id, order_type, order_status, order_date DESC) 
WHERE isactive = 'Y';
