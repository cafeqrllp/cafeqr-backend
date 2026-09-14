-- V1_169: Add Sales History menu item under Insights
INSERT INTO menus (id, name, url, description, parent_id, isactive, created_at, updated_at)
VALUES (
  gen_random_uuid(),
  'Sales History',
  '/owner/sales-history',
  'Sales History & Completed Orders',
  NULL,
  'Y',
  NOW(),
  NOW()
)
ON CONFLICT DO NOTHING;

-- Assign Sales History menu to standard roles
INSERT INTO role_menus (role_id, menu_id)
SELECT r.id, m.id
FROM roles r
CROSS JOIN menus m
WHERE m.url = '/owner/sales-history'
  AND r.name IN ('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF', 'CASHIER', 'OWNER')
ON CONFLICT DO NOTHING;
