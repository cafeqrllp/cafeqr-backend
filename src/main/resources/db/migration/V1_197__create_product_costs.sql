-- Inventory costing: current cost per org / product / variant (ADempiere M_Cost equivalent).
CREATE TABLE IF NOT EXISTS product_costs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id UUID,
    org_id UUID NOT NULL,
    product_id UUID NOT NULL,
    variant_id UUID,
    costing_method VARCHAR(20) NOT NULL DEFAULT 'AVERAGE_PO',
    current_cost_price NUMERIC(19, 6) NOT NULL DEFAULT 0,
    current_qty NUMERIC(15, 3) NOT NULL DEFAULT 0,
    cumulated_amt NUMERIC(19, 6) NOT NULL DEFAULT 0,
    cumulated_qty NUMERIC(15, 3) NOT NULL DEFAULT 0,
    last_purchase_price NUMERIC(19, 6),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_product_costs_org_product_variant
    ON product_costs (org_id, product_id, COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE INDEX IF NOT EXISTS ix_product_costs_client_org ON product_costs (client_id, org_id);
