-- Persist product category names on sales order lines so KOT category routing
-- keeps working for new orders and reprints even after the product catalog changes.
ALTER TABLE order_lines ADD COLUMN IF NOT EXISTS category_name VARCHAR(255);
