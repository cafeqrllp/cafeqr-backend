-- V1_178: Add UPI payment details to organizations and system_configurations

ALTER TABLE organizations ADD COLUMN IF NOT EXISTS upi_id VARCHAR(100);
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS upi_payee_name VARCHAR(150);

ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS upi_id VARCHAR(100);
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS upi_payee_name VARCHAR(150);
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS upi_qr_on_bill_enabled BOOLEAN DEFAULT TRUE;
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS upi_qr_on_pos_enabled BOOLEAN DEFAULT TRUE;
