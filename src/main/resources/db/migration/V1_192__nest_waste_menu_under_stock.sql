-- V1.192: Nest Waste Management under the Stock menu group.
-- Creates the 'Waste Management' menu row if missing, otherwise re-parents it under 'Stock'.
-- Grants it to every role that already has access to 'Stock'.

DO $$
DECLARE
    stock_menu_id UUID;
    waste_menu_id UUID;
BEGIN
    SELECT id INTO stock_menu_id FROM menus WHERE name = 'Stock' AND parent_id IS NULL LIMIT 1;

    IF stock_menu_id IS NULL THEN
        RAISE NOTICE 'Stock parent menu not found, skipping waste menu nesting';
        RETURN;
    END IF;

    SELECT id INTO waste_menu_id FROM menus WHERE name = 'Waste Management' LIMIT 1;

    IF waste_menu_id IS NULL THEN
        waste_menu_id := gen_random_uuid();
        INSERT INTO menus (id, name, url, description, isactive, parent_id)
        VALUES (waste_menu_id, 'Waste Management', '/owner/waste-management', 'Track, analyse and reduce kitchen waste', 'Y', stock_menu_id);
    ELSE
        UPDATE menus
        SET parent_id = stock_menu_id,
            url = '/owner/waste-management'
        WHERE id = waste_menu_id;
    END IF;

    INSERT INTO role_menus (role_id, menu_id)
    SELECT rm.role_id, waste_menu_id
    FROM role_menus rm
    WHERE rm.menu_id = stock_menu_id
    ON CONFLICT DO NOTHING;

    RAISE NOTICE 'V1.192 Waste Management nested under Stock';
END $$;
