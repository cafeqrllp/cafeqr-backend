-- V1_156: Ensure remarks column exists on orders table
ALTER TABLE orders ADD COLUMN IF NOT EXISTS remarks TEXT;
