-- V1_157: Add barcode_scanner_enabled column to system_configurations table
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS barcode_scanner_enabled BOOLEAN DEFAULT FALSE;
