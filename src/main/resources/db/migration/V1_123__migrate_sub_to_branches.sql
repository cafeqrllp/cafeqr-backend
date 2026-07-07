-- V1.123 Migrate Subscription to Branches and create payment tracking table
ALTER TABLE organizations 
ADD COLUMN IF NOT EXISTS subscription_status VARCHAR(50) DEFAULT 'TRIAL',
ADD COLUMN IF NOT EXISTS subscription_expiry_date TIMESTAMP DEFAULT (CURRENT_TIMESTAMP + INTERVAL '14' DAY);

-- Copy existing client subscription status & expiry to organization branches
UPDATE organizations
SET subscription_status = COALESCE(c.subscription_status, 'TRIAL'),
    subscription_expiry_date = COALESCE(c.subscription_expiry_date, CURRENT_TIMESTAMP + INTERVAL '14' DAY)
FROM clients c
WHERE organizations.client_id = c.id;

-- Create subscription_payments table to prevent replay/double activation attacks
CREATE TABLE IF NOT EXISTS subscription_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id UUID NOT NULL,
    org_id UUID,
    payment_id VARCHAR(100) UNIQUE NOT NULL,
    order_id VARCHAR(100) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sub_payment_client FOREIGN KEY (client_id) REFERENCES clients(id) ON DELETE CASCADE
);

-- Backfill active branch-scoped modules (KOT, INVENTORY) for existing branches
INSERT INTO client_subscription_modules (id, client_id, org_id, module_name, status, auto_renew, expiry_date)
SELECT 
    gen_random_uuid(),
    o.client_id,
    o.id,
    m.module_name,
    'ACTIVE',
    true,
    COALESCE(o.subscription_expiry_date, CURRENT_TIMESTAMP + INTERVAL '14' DAY)
FROM organizations o
CROSS JOIN (VALUES ('KOT'), ('INVENTORY')) AS m(module_name)
ON CONFLICT (client_id, org_id, module_name) WHERE org_id IS NOT NULL DO NOTHING;

-- Backfill active client-scoped modules (CRM, CREDIT_LEDGER) for existing clients
INSERT INTO client_subscription_modules (id, client_id, org_id, module_name, status, auto_renew, expiry_date)
SELECT 
    gen_random_uuid(),
    c.id,
    NULL,
    m.module_name,
    'ACTIVE',
    true,
    COALESCE(c.subscription_expiry_date, CURRENT_TIMESTAMP + INTERVAL '14' DAY)
FROM clients c
CROSS JOIN (VALUES ('CRM'), ('CREDIT_LEDGER')) AS m(module_name)
ON CONFLICT (client_id, module_name) WHERE org_id IS NULL DO NOTHING;

-- Align existing branch-scoped module expiry dates with their branch's base subscription expiry date
UPDATE client_subscription_modules
SET expiry_date = o.subscription_expiry_date
FROM organizations o
WHERE client_subscription_modules.org_id = o.id;

-- Align existing client-scoped module expiry dates with their client's base subscription expiry date
UPDATE client_subscription_modules
SET expiry_date = c.subscription_expiry_date
FROM clients c
WHERE client_subscription_modules.org_id IS NULL AND client_subscription_modules.client_id = c.id;
