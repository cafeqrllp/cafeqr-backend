-- Inventory costing settings per client / branch.
-- costing_method: AVERAGE_PO (weighted average on receipt), LAST_PO, STANDARD.
-- cost_includes_tax: whether non-recoverable purchase tax is added to stock cost (country / finance decision).
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS costing_method VARCHAR(20) NOT NULL DEFAULT 'AVERAGE_PO';
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS cost_includes_tax BOOLEAN NOT NULL DEFAULT FALSE;
