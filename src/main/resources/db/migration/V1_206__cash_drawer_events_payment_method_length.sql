-- Align cash_drawer_events.payment_method with orders/expenses (VARCHAR(50)).
ALTER TABLE cash_drawer_events ALTER COLUMN payment_method TYPE VARCHAR(50);
