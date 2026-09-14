-- Flyway Migration V1_168: Performance index for POS credit customer balance calculation
-- Enables sub-millisecond index scans on invoices when searching credit customers in POS

CREATE INDEX IF NOT EXISTS idx_invoices_credit_customer_due
ON invoices (client_id, credit_customer_id, amount_due)
WHERE isactive = 'Y';
