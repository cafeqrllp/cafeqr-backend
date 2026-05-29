-- V1_92: Add Offline Sync Center Menu Item and System Configuration Columns

-- 1. Insert Offline Sync Center into menus
INSERT INTO menus (id, name, url, description, parent_id, isactive, created_at, updated_at)
VALUES (
  gen_random_uuid(),
  'Offline Sync Center',
  '/owner/offline-sync',
  'Manage offline operations queue, manually trigger synchronizations, and adjust network configuration settings.',
  NULL,
  'Y',
  NOW(),
  NOW()
)
ON CONFLICT DO NOTHING;

-- 2. Assign Offline Sync Center menu to SUPER_ADMIN and ADMIN only
INSERT INTO role_menus (role_id, menu_id)
SELECT r.id, m.id
FROM roles r
CROSS JOIN menus m
WHERE m.url = '/owner/offline-sync'
  AND r.name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT DO NOTHING;

-- 3. Add system configuration columns
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS offline_sync_enabled BOOLEAN DEFAULT TRUE;
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS offline_sync_interval INTEGER DEFAULT 60;
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS offline_lease_block_size INTEGER DEFAULT 100;
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS offline_fail_open_payments BOOLEAN DEFAULT FALSE;
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS offline_local_encryption BOOLEAN DEFAULT FALSE;
