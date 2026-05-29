-- Flyway migration V1_95: Add unique constraint on order_id in invoices table
-- This enforces database-level idempotency and prevents concurrent double billing race conditions.
ALTER TABLE invoices DROP CONSTRAINT IF EXISTS uq_invoices_order_id;
ALTER TABLE invoices ADD CONSTRAINT uq_invoices_order_id UNIQUE (order_id);
