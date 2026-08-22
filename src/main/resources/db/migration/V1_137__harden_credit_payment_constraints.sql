-- Migration script to add database constraints and composite indexes for Credit Payment hardening

-- Check constraints for payments and allocations
-- NOT VALID: constraint is enforced on new rows only, existing rows are not re-scanned.
-- This prevents migration failure when legacy rows have amount_paid = 0 or NULL.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.constraint_column_usage
        WHERE table_name = 'payments' AND constraint_name = 'chk_payments_amount_paid_positive'
    ) THEN
        ALTER TABLE payments
            ADD CONSTRAINT chk_payments_amount_paid_positive CHECK (amount_paid > 0) NOT VALID;
    END IF;
END$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.constraint_column_usage
        WHERE table_name = 'payment_allocations' AND constraint_name = 'chk_payment_allocations_amount_positive'
    ) THEN
        ALTER TABLE payment_allocations
            ADD CONSTRAINT chk_payment_allocations_amount_positive CHECK (allocated_amount > 0) NOT VALID;
    END IF;
END$$;

-- Composite indexes for tenant-scoped credit queries
CREATE INDEX IF NOT EXISTS idx_payments_tenant_credit_cust
    ON payments(client_id, org_id, credit_customer_id, payment_date);

CREATE INDEX IF NOT EXISTS idx_payment_allocations_credit_cust_inv
    ON payment_allocations(client_id, credit_customer_id, invoice_id);

CREATE INDEX IF NOT EXISTS idx_credit_customers_client_id_org_id
    ON credit_customers(client_id, org_id, status);
