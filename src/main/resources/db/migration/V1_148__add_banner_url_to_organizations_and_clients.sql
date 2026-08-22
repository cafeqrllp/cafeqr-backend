-- V1_148: Add banner_url column to organizations and clients tables
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS banner_url TEXT DEFAULT NULL;
ALTER TABLE clients ADD COLUMN IF NOT EXISTS banner_url TEXT DEFAULT NULL;
