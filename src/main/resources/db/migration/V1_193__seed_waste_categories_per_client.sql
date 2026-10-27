-- V1.193: V1.53 seeded the default waste categories without a client_id, so the
-- tenant-scoped category list never returned them. Give every client its own copy
-- of the defaults, skipping any the client already has.

INSERT INTO waste_categories (id, client_id, name, icon, sort_order, isactive)
SELECT gen_random_uuid(), c.id, d.name, d.icon, d.sort_order, 'Y'
FROM clients c
CROSS JOIN (VALUES
    ('Spillage',           'FaTint',                1),
    ('Burnt / Overcooked', 'FaFire',                2),
    ('Expired / Spoiled',  'FaExclamationTriangle', 3),
    ('Customer Return',    'FaUndo',                4),
    ('Over-preparation',   'FaBoxOpen',             5),
    ('Other',              'FaEllipsisH',           6)
) AS d(name, icon, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM waste_categories wc
    WHERE wc.client_id = c.id AND wc.name = d.name
);
