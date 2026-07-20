-- V1_125: Add description/notes column to order_lines for per-item kitchen notes (KOT)
ALTER TABLE order_lines ADD COLUMN IF NOT EXISTS description VARCHAR(500);
