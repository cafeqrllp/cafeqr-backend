-- Repair deployed or local databases where V1_102 was missed or recorded
-- without the default billing UI column being present.

ALTER TABLE system_configurations
    ADD COLUMN IF NOT EXISTS default_billing_ui_mode VARCHAR(20);

ALTER TABLE system_configurations
    ALTER COLUMN default_billing_ui_mode SET DEFAULT 'standard';

UPDATE system_configurations
SET default_billing_ui_mode = 'standard'
WHERE default_billing_ui_mode IS NULL;
