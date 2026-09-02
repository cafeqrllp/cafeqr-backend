ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS dine_in_auto_print_kot_on_settle BOOLEAN DEFAULT FALSE;
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS dine_in_hide_kitchen_mode BOOLEAN DEFAULT FALSE;
