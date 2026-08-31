-- V1_154: Add reviews_enabled column to organizations table
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS reviews_enabled BOOLEAN DEFAULT TRUE;
