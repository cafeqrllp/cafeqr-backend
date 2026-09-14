-- V1_165: Add payroll_enabled column to system_configurations table
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS payroll_enabled BOOLEAN DEFAULT FALSE;
