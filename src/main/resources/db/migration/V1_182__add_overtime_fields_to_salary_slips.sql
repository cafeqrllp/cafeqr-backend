-- V1_182: Add regular_hours, overtime_hours, and overtime_pay columns to hr_salary_slips
ALTER TABLE hr_salary_slips ADD COLUMN IF NOT EXISTS regular_hours NUMERIC(10, 2) DEFAULT 0;
ALTER TABLE hr_salary_slips ADD COLUMN IF NOT EXISTS overtime_hours NUMERIC(10, 2) DEFAULT 0;
ALTER TABLE hr_salary_slips ADD COLUMN IF NOT EXISTS overtime_pay NUMERIC(19, 2) DEFAULT 0;
