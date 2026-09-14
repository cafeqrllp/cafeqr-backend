-- Add pin_code column to hr_employees table if it does not exist
ALTER TABLE hr_employees ADD COLUMN IF NOT EXISTS pin_code VARCHAR(20);
