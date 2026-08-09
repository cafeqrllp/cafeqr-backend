-- V1_135: Grant Purchase Orders menu to all administrative roles across all tenants

INSERT INTO menus (id, name, url, description, parent_id, isactive, created_at, updated_at)
SELECT gen_random_uuid(), 'Purchase Orders', '/owner/purchase-orders', 'Purchase Order Management', NULL, 'Y', NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM menus WHERE url = '/owner/purchase-orders' OR name = 'Purchase Orders'
);

-- Grant Purchase Orders to SUPER_ADMIN, ADMIN, OWNER, MANAGER, and STAFF roles (both global and tenant-specific)
INSERT INTO role_menus (role_id, menu_id)
SELECT r.id, m.id
FROM roles r
CROSS JOIN menus m
WHERE m.name = 'Purchase Orders'
  AND UPPER(r.name) IN ('SUPER_ADMIN', 'ADMIN', 'OWNER', 'MANAGER', 'STAFF')
ON CONFLICT DO NOTHING;
