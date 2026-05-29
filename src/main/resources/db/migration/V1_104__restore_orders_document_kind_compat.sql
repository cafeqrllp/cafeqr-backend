-- Restore orders.document_kind for deployed/stale backend builds that still
-- map this legacy column. Current code no longer depends on it, but keeping
-- the column populated is harmless and prevents runtime SQL grammar failures.

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS document_kind VARCHAR(50);

UPDATE orders
SET document_kind = CASE
    WHEN order_type = 'SALE' THEN 'SALE_ORDER'
    WHEN order_type = 'PURCHASE' THEN 'PURCHASE_ORDER'
    WHEN order_type = 'EXPENSE' THEN 'EXPENSE'
    ELSE document_kind
END
WHERE document_kind IS NULL;
