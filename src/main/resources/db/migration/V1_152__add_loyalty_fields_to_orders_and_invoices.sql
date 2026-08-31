-- V1_145: Add loyalty redemption fields to orders and invoices tables
ALTER TABLE orders ADD COLUMN IF NOT EXISTS redeem_points INTEGER DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS loyalty_amount NUMERIC(15, 2) DEFAULT 0.00;

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS redeem_points INTEGER DEFAULT 0;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS loyalty_amount NUMERIC(15, 2) DEFAULT 0.00;
