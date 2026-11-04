-- V1.195 Enhance subscription_payments with branch attribution and itemized metadata
ALTER TABLE subscription_payments
ADD COLUMN IF NOT EXISTS payment_method VARCHAR(50) DEFAULT 'RAZORPAY',
ADD COLUMN IF NOT EXISTS description VARCHAR(255),
ADD COLUMN IF NOT EXISTS billing_cycle VARCHAR(50) DEFAULT 'ANNUAL',
ADD COLUMN IF NOT EXISTS status VARCHAR(50) DEFAULT 'PAID';

CREATE INDEX IF NOT EXISTS idx_sub_payments_org_id ON subscription_payments(org_id);
CREATE INDEX IF NOT EXISTS idx_sub_payments_client_id ON subscription_payments(client_id);
