-- V1_144: Add dedicated remarks column to orders for kitchen notes / order remarks
ALTER TABLE orders ADD COLUMN IF NOT EXISTS remarks TEXT;

-- Backfill existing plain-text descriptions into remarks (skip structured delivery strings with name:/address: and system payment notes)
UPDATE orders
SET remarks = description
WHERE description IS NOT NULL
  AND description <> ''
  AND (remarks IS NULL OR remarks = '')
  AND description NOT LIKE '%name:%'
  AND description NOT LIKE '%address:%'
  AND description NOT LIKE '%Purchase Payment%';
