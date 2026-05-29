-- Repair deployed or local databases where credit-order schema columns were
-- missed or recorded without all current entity columns being present.

CREATE TABLE IF NOT EXISTS credit_customers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id UUID NOT NULL REFERENCES clients(id),
    org_id UUID REFERENCES organizations(id),
    linked_customer_id UUID REFERENCES customers(id),
    name VARCHAR(200) NOT NULL,
    phone VARCHAR(50),
    email VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    credit_limit NUMERIC(15, 2) NOT NULL DEFAULT 0,
    opening_balance NUMERIC(15, 2) NOT NULL DEFAULT 0,
    notes TEXT,
    isactive CHAR(1) NOT NULL DEFAULT 'Y',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_credit_customers_client_status
    ON credit_customers(client_id, status, isactive, name);

CREATE UNIQUE INDEX IF NOT EXISTS ux_credit_customers_client_phone_active
    ON credit_customers(client_id, phone)
    WHERE phone IS NOT NULL AND phone <> '' AND isactive = 'Y';

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS is_credit BOOLEAN DEFAULT FALSE;

ALTER TABLE orders
    ALTER COLUMN is_credit SET DEFAULT FALSE;

UPDATE orders
SET is_credit = FALSE
WHERE is_credit IS NULL;

ALTER TABLE orders
    ALTER COLUMN is_credit SET NOT NULL;

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS credit_customer_id UUID REFERENCES credit_customers(id);

ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS is_credit BOOLEAN DEFAULT FALSE;

ALTER TABLE invoices
    ALTER COLUMN is_credit SET DEFAULT FALSE;

UPDATE invoices
SET is_credit = FALSE
WHERE is_credit IS NULL;

ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS credit_customer_id UUID REFERENCES credit_customers(id);

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS customer_id UUID REFERENCES customers(id);

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS credit_customer_id UUID REFERENCES credit_customers(id);

ALTER TABLE IF EXISTS payment_allocations
    ADD COLUMN IF NOT EXISTS credit_customer_id UUID REFERENCES credit_customers(id);

ALTER TABLE system_configurations
    ADD COLUMN IF NOT EXISTS credit_allocation_mode VARCHAR(30) DEFAULT 'OLDEST_FIRST';

ALTER TABLE system_configurations
    ALTER COLUMN credit_allocation_mode SET DEFAULT 'OLDEST_FIRST';

UPDATE system_configurations
SET credit_allocation_mode = 'OLDEST_FIRST'
WHERE credit_allocation_mode IS NULL OR credit_allocation_mode = '';
