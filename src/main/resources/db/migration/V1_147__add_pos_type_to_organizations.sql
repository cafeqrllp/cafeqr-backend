-- V1_147: Add pos_type column to organizations table
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS pos_type VARCHAR(50) DEFAULT NULL;
