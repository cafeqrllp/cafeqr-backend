-- V1.191: Every client gets a default UOM "Each" (symbol "Ea", 0 decimal places) if it has
-- never set up any UOM. Clients that already have UOMs are left unchanged.

INSERT INTO uoms (id, name, short_name, is_active, is_default, uom_precision, client_id, org_id, created_at, updated_at, created_by)
SELECT gen_random_uuid(), 'Each', 'Ea', TRUE, TRUE, 0, c.id, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'
FROM clients c
WHERE NOT EXISTS (SELECT 1 FROM uoms u WHERE u.client_id = c.id);
