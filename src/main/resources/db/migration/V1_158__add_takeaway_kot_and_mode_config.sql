-- V1_158: Add takeaway_auto_print_kot_on_settle and takeaway_hide_kitchen_mode columns to system_configurations table
ALTER TABLE system_configurations 
  ADD COLUMN IF NOT EXISTS takeaway_auto_print_kot_on_settle BOOLEAN DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS takeaway_hide_kitchen_mode BOOLEAN DEFAULT FALSE;
