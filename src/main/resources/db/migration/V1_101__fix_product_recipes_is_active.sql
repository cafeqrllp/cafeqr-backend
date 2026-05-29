-- V1_101: Fix product_recipes active column to match standard is_active BOOLEAN mapping
ALTER TABLE product_recipes DROP COLUMN IF EXISTS isactive;
ALTER TABLE product_recipes ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
