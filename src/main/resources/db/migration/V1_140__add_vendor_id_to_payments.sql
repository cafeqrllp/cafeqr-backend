-- Migration: Drop foreign key constraint on payments.credit_customer_id
-- allowing credit_customer_id to act as unified partner ID for both Customers and Vendors
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_credit_customer_id_fkey;

CREATE INDEX IF NOT EXISTS idx_payments_credit_cust_type_date
    ON payments(client_id, credit_customer_id, payment_type, payment_date);
