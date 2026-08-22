-- V1_143: Reset vendor opening_balance to 0
-- Legacy frontend code accidentally saved running balances into vendor.opening_balance on every payment/settlement.
-- Since Balance Owed is calculated as (opening_balance + sum of unpaid invoice amountDue),
-- having non-zero values in opening_balance caused double counting (e.g., opening_balance=2000 + invoice amountDue=2000 = 4000 balance owed).

UPDATE vendors
SET opening_balance = 0
WHERE opening_balance IS NOT NULL AND opening_balance <> 0;
