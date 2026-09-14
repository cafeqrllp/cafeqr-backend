-- V1_167__add_auditing_to_punch_segments.sql

ALTER TABLE hr_punch_segments ADD COLUMN IF NOT EXISTS created_by VARCHAR(255);
ALTER TABLE hr_punch_segments ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);
ALTER TABLE hr_attendance ALTER COLUMN clock_in_time DROP NOT NULL;

