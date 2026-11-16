-- Inventory costing: stock_ledgers doubles as the cost detail (ADempiere M_CostDetail equivalent).
ALTER TABLE stock_ledgers ALTER COLUMN unit_cost TYPE NUMERIC(19, 6);
ALTER TABLE stock_ledgers ADD COLUMN IF NOT EXISTS total_cost NUMERIC(19, 6);
ALTER TABLE stock_ledgers ADD COLUMN IF NOT EXISTS cost_before NUMERIC(19, 6);
ALTER TABLE stock_ledgers ADD COLUMN IF NOT EXISTS cost_after NUMERIC(19, 6);

UPDATE stock_ledgers SET total_cost = ROUND(COALESCE(unit_cost, 0) * quantity_change, 6) WHERE total_cost IS NULL;

CREATE INDEX IF NOT EXISTS ix_stock_ledgers_reference_type ON stock_ledgers (reference_id, transaction_type);
