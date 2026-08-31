-- V1_144: Loyalty Module
-- Creates loyalty programs, earn/redemption rules,
-- customer loyalty accounts, and the immutable transaction ledger.

-- ─── loyalty_program ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS loyalty_program (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id   UUID NOT NULL REFERENCES clients(id),
    org_id      UUID REFERENCES organizations(id),
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,
    priority    INTEGER NOT NULL DEFAULT 10,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255)
);

-- Only ONE default program per (client, org) at any time — DB-enforced
CREATE UNIQUE INDEX IF NOT EXISTS ux_loyalty_program_client_org_default
    ON loyalty_program(client_id, COALESCE(org_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE is_default = TRUE;

CREATE INDEX IF NOT EXISTS idx_loyalty_program_client
    ON loyalty_program(client_id, is_active);

-- ─── loyalty_earn_rule ──────────────────────────────────────────────────────
-- e.g. every ₹100 spent → 1 point
CREATE TABLE IF NOT EXISTS loyalty_earn_rule (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id   UUID NOT NULL REFERENCES loyalty_program(id) ON DELETE CASCADE,
    spend_amount NUMERIC(15, 2) NOT NULL,   -- ₹ threshold (e.g. 100)
    earn_points  INTEGER NOT NULL,           -- points awarded (e.g. 1)
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ─── loyalty_redemption_rule ────────────────────────────────────────────────
-- e.g. 100 points → ₹10 discount
CREATE TABLE IF NOT EXISTS loyalty_redemption_rule (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id           UUID NOT NULL REFERENCES loyalty_program(id) ON DELETE CASCADE,
    points_required      INTEGER NOT NULL,            -- points needed (e.g. 100)
    discount_amount      NUMERIC(15, 2) NOT NULL,    -- discount value (e.g. 10)
    min_points           INTEGER NOT NULL DEFAULT 0, -- minimum to redeem
    max_points_per_order INTEGER,                    -- NULL = unlimited
    allow_partial        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ─── customer_loyalty ───────────────────────────────────────────────────────
-- One account per customer per (client, org). Balance maintained in sync with ledger.
CREATE TABLE IF NOT EXISTS customer_loyalty (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id       UUID NOT NULL REFERENCES customers(id),
    client_id         UUID NOT NULL REFERENCES clients(id),
    org_id            UUID REFERENCES organizations(id),
    program_id        UUID REFERENCES loyalty_program(id),
    current_points    INTEGER NOT NULL DEFAULT 0,
    lifetime_earned   INTEGER NOT NULL DEFAULT 0,
    lifetime_redeemed INTEGER NOT NULL DEFAULT 0,
    version           BIGINT NOT NULL DEFAULT 0,   -- optimistic lock
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_customer_loyalty_customer_client_org
    ON customer_loyalty(customer_id, client_id, COALESCE(org_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE INDEX IF NOT EXISTS idx_customer_loyalty_customer
    ON customer_loyalty(customer_id, client_id);

-- ─── loyalty_transaction ────────────────────────────────────────────────────
-- Immutable audit ledger. NEVER delete or update rows here — only INSERT.
-- Reversals are new rows referencing the original via reference_transaction_id.
CREATE TABLE IF NOT EXISTS loyalty_transaction (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_loyalty_id      UUID NOT NULL REFERENCES customer_loyalty(id),
    customer_id              UUID NOT NULL REFERENCES customers(id),
    client_id                UUID NOT NULL REFERENCES clients(id),
    org_id                   UUID REFERENCES organizations(id),
    program_id               UUID REFERENCES loyalty_program(id),
    order_id                 UUID REFERENCES orders(id),
    transaction_type         VARCHAR(20) NOT NULL,  -- EARN|REDEEM|ADJUSTMENT|EXPIRE|REVERSAL
    points                   INTEGER NOT NULL,       -- positive = credit, negative = debit
    balance_after            INTEGER NOT NULL,
    reference_transaction_id UUID REFERENCES loyalty_transaction(id),
    remarks                  TEXT,
    created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_loyalty_txn_customer
    ON loyalty_transaction(customer_id, client_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_loyalty_txn_order
    ON loyalty_transaction(order_id) WHERE order_id IS NOT NULL;

-- ─── Loyalty menu entry ─────────────────────────────────────────────────────
INSERT INTO menus (id, name, url, description, parent_id, isactive, created_at, updated_at)
SELECT gen_random_uuid(), 'Loyalty', '/owner/loyalty', 'Loyalty programs & customer rewards', NULL, 'Y', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM menus WHERE url = '/owner/loyalty');

-- Assign to OWNER and MANAGER roles
INSERT INTO role_menus (role_id, menu_id)
SELECT r.id, m.id
FROM roles r
CROSS JOIN menus m
WHERE m.url = '/owner/loyalty'
  AND r.name IN ('SUPER_ADMIN', 'ADMIN', 'MANAGER')
ON CONFLICT DO NOTHING;
