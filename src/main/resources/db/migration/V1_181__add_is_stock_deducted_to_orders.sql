-- Add is_stock_deducted flag to orders table.
-- This tracks whether stock has already been deducted for a SALE order,
-- preventing duplicate deductions across create/update/settle flows.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS is_stock_deducted BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill: mark all existing COMPLETED+PAID sales as already deducted
UPDATE orders
SET is_stock_deducted = TRUE
WHERE order_type = 'SALE'
  AND order_status = 'COMPLETED'
  AND payment_status = 'PAID';
