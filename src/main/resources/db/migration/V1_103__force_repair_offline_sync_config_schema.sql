-- Force repair for deployed databases that still missed the offline sync
-- configuration columns after V1_102. This migration is intentionally
-- idempotent so it is safe on already-repaired databases.

ALTER TABLE system_configurations
    ADD COLUMN IF NOT EXISTS offline_sync_enabled BOOLEAN DEFAULT TRUE;

ALTER TABLE system_configurations
    ADD COLUMN IF NOT EXISTS offline_sync_interval INTEGER DEFAULT 60;

ALTER TABLE system_configurations
    ADD COLUMN IF NOT EXISTS offline_lease_block_size INTEGER DEFAULT 100;

ALTER TABLE system_configurations
    ADD COLUMN IF NOT EXISTS offline_fail_open_payments BOOLEAN DEFAULT FALSE;

ALTER TABLE system_configurations
    ADD COLUMN IF NOT EXISTS offline_local_encryption BOOLEAN DEFAULT FALSE;

ALTER TABLE system_configurations
    ALTER COLUMN offline_sync_enabled SET DEFAULT TRUE;

ALTER TABLE system_configurations
    ALTER COLUMN offline_sync_interval SET DEFAULT 60;

ALTER TABLE system_configurations
    ALTER COLUMN offline_lease_block_size SET DEFAULT 100;

ALTER TABLE system_configurations
    ALTER COLUMN offline_fail_open_payments SET DEFAULT FALSE;

ALTER TABLE system_configurations
    ALTER COLUMN offline_local_encryption SET DEFAULT FALSE;

UPDATE system_configurations
SET
    offline_sync_enabled = COALESCE(offline_sync_enabled, TRUE),
    offline_sync_interval = COALESCE(offline_sync_interval, 60),
    offline_lease_block_size = COALESCE(offline_lease_block_size, 100),
    offline_fail_open_payments = COALESCE(offline_fail_open_payments, FALSE),
    offline_local_encryption = COALESCE(offline_local_encryption, FALSE);

INSERT INTO menus (id, name, url, description, parent_id, isactive, created_at, updated_at)
SELECT
    gen_random_uuid(),
    'Offline Sync Center',
    '/owner/offline-sync',
    'Manage offline operations queue, manually trigger synchronizations, and adjust network configuration settings.',
    NULL,
    'Y',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1
    FROM menus
    WHERE url = '/owner/offline-sync'
);

INSERT INTO role_menus (role_id, menu_id)
SELECT r.id, m.id
FROM roles r
CROSS JOIN menus m
WHERE m.url = '/owner/offline-sync'
  AND r.name IN ('SUPER_ADMIN', 'ADMIN')
  AND NOT EXISTS (
      SELECT 1
      FROM role_menus rm
      WHERE rm.role_id = r.id
        AND rm.menu_id = m.id
  );
