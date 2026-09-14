CREATE TABLE IF NOT EXISTS hr_settings (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    org_id UUID,
    standard_hours_per_day DECIMAL(5,2) NOT NULL DEFAULT 8.00,
    overtime_multiplier DECIMAL(4,2) NOT NULL DEFAULT 1.50,
    weekly_overtime_threshold DECIMAL(5,2) NOT NULL DEFAULT 40.00,
    overtime_mode VARCHAR(20) NOT NULL DEFAULT 'DAILY',
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    CONSTRAINT uq_hr_settings_client_org UNIQUE (client_id, org_id)
);
