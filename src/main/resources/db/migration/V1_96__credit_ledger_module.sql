-- V1_92: Credit ledger module.
-- Credit customers are client-wide, while the invoices/orders/payments that use
-- them remain branch-scoped through their existing org_id columns.

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

ALTER TABLE orders ADD COLUMN IF NOT EXISTS is_credit BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS credit_customer_id UUID REFERENCES credit_customers(id);

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS credit_customer_id UUID REFERENCES credit_customers(id);

ALTER TABLE payments ADD COLUMN IF NOT EXISTS customer_id UUID REFERENCES customers(id);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS credit_customer_id UUID REFERENCES credit_customers(id);

ALTER TABLE payment_allocations ADD COLUMN IF NOT EXISTS credit_customer_id UUID REFERENCES credit_customers(id);

ALTER TABLE system_configurations
    ADD COLUMN IF NOT EXISTS credit_allocation_mode VARCHAR(30) NOT NULL DEFAULT 'OLDEST_FIRST';

UPDATE system_configurations
SET credit_allocation_mode = 'OLDEST_FIRST'
WHERE credit_allocation_mode IS NULL OR credit_allocation_mode = '';

INSERT INTO credit_customers (
    id,
    client_id,
    org_id,
    linked_customer_id,
    name,
    phone,
    email,
    status,
    credit_limit,
    opening_balance,
    isactive,
    created_at,
    updated_at,
    created_by,
    updated_by
)
SELECT
    gen_random_uuid(),
    c.client_id,
    NULL,
    c.id,
    c.name,
    c.phone,
    c.email,
    CASE WHEN c.isactive = 'N' THEN 'SUSPENDED' ELSE 'ACTIVE' END,
    COALESCE(c.credit_limit, 0),
    COALESCE(c.opening_balance, 0),
    c.isactive,
    COALESCE(c.created_at, CURRENT_TIMESTAMP),
    COALESCE(c.updated_at, CURRENT_TIMESTAMP),
    c.created_by,
    c.updated_by
FROM (
    SELECT DISTINCT ON (c.client_id, COALESCE(NULLIF(c.phone, ''), c.id::text))
        c.*
    FROM customers c
    WHERE UPPER(COALESCE(c.customer_category, '')) = 'CREDIT'
    ORDER BY c.client_id, COALESCE(NULLIF(c.phone, ''), c.id::text), c.created_at ASC NULLS LAST
) c
WHERE 1 = 1
  AND NOT EXISTS (
      SELECT 1
      FROM credit_customers cc
      WHERE cc.client_id = c.client_id
        AND cc.linked_customer_id = c.id
  )
  AND (
      c.phone IS NULL
      OR c.phone = ''
      OR NOT EXISTS (
          SELECT 1
          FROM credit_customers cc
          WHERE cc.client_id = c.client_id
            AND cc.phone = c.phone
            AND cc.isactive = 'Y'
      )
  );

UPDATE orders o
SET credit_customer_id = cc.id,
    is_credit = TRUE
FROM credit_customers cc
WHERE o.client_id = cc.client_id
  AND o.customer_id = cc.linked_customer_id
  AND o.order_type = 'SALE'
  AND UPPER(COALESCE(o.payment_status, '')) IN ('PENDING', 'PARTIAL')
  AND o.credit_customer_id IS NULL;

UPDATE invoices i
SET credit_customer_id = cc.id,
    is_credit = TRUE
FROM credit_customers cc
WHERE i.client_id = cc.client_id
  AND i.customer_id = cc.linked_customer_id
  AND i.invoice_type = 'CUSTOMER_INVOICE'
  AND (
      COALESCE(i.is_credit, FALSE) = TRUE
      OR UPPER(COALESCE(i.status, '')) IN ('UNPAID', 'PARTIAL')
  )
  AND i.credit_customer_id IS NULL;

UPDATE payments p
SET customer_id = o.customer_id,
    credit_customer_id = o.credit_customer_id
FROM orders o
WHERE p.order_id = o.id
  AND p.client_id = o.client_id
  AND p.customer_id IS NULL;

INSERT INTO menus (id, name, url, description, parent_id, isactive, created_at, updated_at)
SELECT gen_random_uuid(), 'Credit Customers', '/owner/credit-customers', 'Customer credit ledger management', NULL, 'Y', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM menus WHERE url = '/owner/credit-customers'
);

INSERT INTO role_menus (role_id, menu_id)
SELECT r.id, m.id
FROM roles r
CROSS JOIN menus m
WHERE m.url = '/owner/credit-customers'
  AND r.name IN ('SUPER_ADMIN', 'ADMIN', 'MANAGER')
ON CONFLICT DO NOTHING;
