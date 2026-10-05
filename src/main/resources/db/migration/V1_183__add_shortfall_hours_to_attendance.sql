-- Add shortfall_hours column to hr_attendance table
ALTER TABLE hr_attendance
ADD COLUMN IF NOT EXISTS shortfall_hours NUMERIC(5,2) DEFAULT 0.00;
