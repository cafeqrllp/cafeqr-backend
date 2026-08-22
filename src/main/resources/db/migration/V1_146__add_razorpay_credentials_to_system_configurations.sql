-- V1_146: Add Razorpay BYO-PG credentials columns to system_configurations table
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS razorpay_key_id VARCHAR(100) DEFAULT NULL;
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS razorpay_key_secret VARCHAR(100) DEFAULT NULL;
