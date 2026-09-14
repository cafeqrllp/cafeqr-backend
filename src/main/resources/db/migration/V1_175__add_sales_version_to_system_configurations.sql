-- V1_175: Add sales version and POS v2 toggle to system configurations (default: v1 and board view)
DO $$ 
BEGIN 
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='system_configurations' AND column_name='sales_version') THEN
        ALTER TABLE system_configurations ADD COLUMN sales_version VARCHAR(20) DEFAULT 'v1';
    ELSE
        ALTER TABLE system_configurations ALTER COLUMN sales_version SET DEFAULT 'v1';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='system_configurations' AND column_name='pos_v2_enabled') THEN
        ALTER TABLE system_configurations ADD COLUMN pos_v2_enabled BOOLEAN DEFAULT FALSE;
    ELSE
        ALTER TABLE system_configurations ALTER COLUMN pos_v2_enabled SET DEFAULT FALSE;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='system_configurations' AND column_name='default_billing_ui_mode') THEN
        ALTER TABLE system_configurations ALTER COLUMN default_billing_ui_mode SET DEFAULT 'board';
    END IF;

    -- Ensure default for existing configurations is classic sales (v1)
    UPDATE system_configurations 
    SET sales_version = 'v1', pos_v2_enabled = FALSE 
    WHERE sales_version IS NULL OR sales_version != 'v2';
END $$;
