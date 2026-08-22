-- V1_149: Add slug columns with auto-backfill for clean, human-readable URLs

-- 1. Add slug to clients
ALTER TABLE clients ADD COLUMN IF NOT EXISTS slug VARCHAR(100);

-- Backfill client slugs from name if null
UPDATE clients
SET slug = LOWER(TRIM(BOTH '-' FROM REGEXP_REPLACE(COALESCE(NULLIF(name, ''), 'store'), '[^a-zA-Z0-9]+', '-', 'g')))
WHERE slug IS NULL OR slug = '';

-- Ensure client slug uniqueness by appending short id suffix where duplicates exist
WITH duplicates AS (
    SELECT id, slug, ROW_NUMBER() OVER (PARTITION BY slug ORDER BY created_at ASC) as rnum
    FROM clients
)
UPDATE clients c
SET slug = c.slug || '-' || SUBSTRING(c.id::text, 1, 4)
FROM duplicates d
WHERE c.id = d.id AND d.rnum > 1;

-- Add index on client slug
CREATE UNIQUE INDEX IF NOT EXISTS idx_clients_slug ON clients(LOWER(slug));


-- 2. Add slug to organizations
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS slug VARCHAR(100);

-- Backfill organization slugs from name or branch_code
UPDATE organizations
SET slug = LOWER(TRIM(BOTH '-' FROM REGEXP_REPLACE(COALESCE(NULLIF(name, ''), NULLIF(branch_code, ''), 'branch'), '[^a-zA-Z0-9]+', '-', 'g')))
WHERE slug IS NULL OR slug = '';

-- Ensure organization slug uniqueness within client
WITH org_duplicates AS (
    SELECT id, client_id, slug, ROW_NUMBER() OVER (PARTITION BY client_id, slug ORDER BY created_at ASC) as rnum
    FROM organizations
)
UPDATE organizations o
SET slug = o.slug || '-' || SUBSTRING(o.id::text, 1, 4)
FROM org_duplicates d
WHERE o.id = d.id AND d.rnum > 1;

-- Add index on organization slug
CREATE INDEX IF NOT EXISTS idx_organizations_client_slug ON organizations(client_id, LOWER(slug));
CREATE INDEX IF NOT EXISTS idx_organizations_slug ON organizations(LOWER(slug));
