-- V1_141: Fix corrupt vendor bills and vendor opening balances
-- Root cause: prior buggy code set creditCustomerId = vendorId on VENDOR_BILL invoices
--             and also decremented vendor opening_balance on every settlement.
-- Fix 1: Clear creditCustomerId on VENDOR_BILL invoices that already have vendorId set
UPDATE invoices
SET credit_customer_id = NULL
WHERE invoice_type = 'VENDOR_BILL'
  AND vendor_id IS NOT NULL
  AND credit_customer_id IS NOT NULL
  AND vendor_id = credit_customer_id;

-- Fix 2: Reset vendor opening_balance to 0 for vendors that had their balance
--         corrupted by the wrong PUT /vendors/{id} openingBalance update in frontend.
--         (Balance Owed is now computed dynamically from invoice amountDue - no openingBalance needed)
-- NOTE: If a vendor had a GENUINE pre-existing opening balance, it needs to be manually restored.
-- This migration sets to 0 only vendors where opening_balance was clearly corrupted
-- (i.e., less than 0 due to over-deduction).
UPDATE vendors
SET opening_balance = 0
WHERE opening_balance < 0;
