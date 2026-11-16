-- Purchase tax is never part of stock cost: remove the per-branch "Include Purchase Tax in Cost" setting
-- that V1_199 added.
ALTER TABLE system_configurations DROP COLUMN IF EXISTS cost_includes_tax;
