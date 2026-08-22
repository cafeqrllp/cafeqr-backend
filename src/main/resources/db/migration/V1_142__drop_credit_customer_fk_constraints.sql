-- Migration V1_142: Drop foreign key constraints on credit_customer_id across payments, payment_allocations, invoices, and orders.
-- Reason: credit_customer_id is a unified partner reference column used for both Credit Customers and Vendors.
-- Restricting it via FK to credit_customers(id) causes FK violations when recording vendor bills, vendor payments, and vendor allocations.

ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_credit_customer_id_fkey;
ALTER TABLE payment_allocations DROP CONSTRAINT IF EXISTS payment_allocations_credit_customer_id_fkey;
ALTER TABLE invoices DROP CONSTRAINT IF EXISTS invoices_credit_customer_id_fkey;
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_credit_customer_id_fkey;

-- Dynamic drop block in case constraint names were auto-generated differently by Postgres
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT tc.table_name, tc.constraint_name
        FROM information_schema.table_constraints AS tc
        JOIN information_schema.key_column_usage AS kcu
          ON tc.constraint_name = kcu.constraint_name
          AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY'
          AND kcu.column_name = 'credit_customer_id'
          AND tc.table_name IN ('payments', 'payment_allocations', 'invoices', 'orders')
    ) LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT IF EXISTS %I', r.table_name, r.constraint_name);
    END LOOP;
END $$;
