-- Sidebar entry for the Cash Drawer History report (split out of V1_202, which had already been applied).
-- Granted to every role that has the "View Drawer History" permission.
INSERT INTO menus (id, name, url, description, parent_id, isactive, created_at, updated_at)
SELECT gen_random_uuid(), 'Cash Drawer History', '/owner/cash-drawer-history',
       'Audit trail of every cash drawer open: who, when, terminal, trigger and reason.', NULL, 'Y', NOW(), NOW()
 WHERE NOT EXISTS (SELECT 1 FROM menus WHERE url = '/owner/cash-drawer-history');

INSERT INTO role_menus (role_id, menu_id)
SELECT r.id, m.id
  FROM roles r
  JOIN menus m ON m.url = '/owner/cash-drawer-history'
 WHERE r.can_view_cash_drawer_history = TRUE
   AND NOT EXISTS (SELECT 1 FROM role_menus rm WHERE rm.role_id = r.id AND rm.menu_id = m.id);
