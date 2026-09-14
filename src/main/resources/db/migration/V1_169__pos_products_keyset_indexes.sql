-- ===========================================================================
-- V1_166: Keyset pagination indexes for POS Product Catalog with Org-level isolation
-- ===========================================================================

-- 1. Deterministic keyset index for POS product catalog browsing (name ASC, id ASC)
CREATE INDEX IF NOT EXISTS idx_products_pos_keyset_page
ON products (client_id, org_id, is_active, name ASC, id ASC)
WHERE is_active = true AND (is_ingredient IS FALSE OR is_ingredient IS NULL);

-- 2. Category-filtered keyset pagination index
CREATE INDEX IF NOT EXISTS idx_products_pos_category_keyset
ON products (client_id, org_id, category_id, is_active, name ASC, id ASC)
WHERE is_active = true AND (is_ingredient IS FALSE OR is_ingredient IS NULL);

-- 3. Barcode exact lookup index for sub-millisecond barcode scans
CREATE INDEX IF NOT EXISTS idx_products_pos_barcode_exact
ON products (client_id, org_id, barcode)
WHERE is_active = true AND barcode IS NOT NULL;
