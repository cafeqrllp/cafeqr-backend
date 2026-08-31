-- V1_153: Add social media link columns to organizations table
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS instagram_url VARCHAR(512) DEFAULT NULL;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS whatsapp_number VARCHAR(100) DEFAULT NULL;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS twitter_url VARCHAR(512) DEFAULT NULL;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS facebook_url VARCHAR(512) DEFAULT NULL;
