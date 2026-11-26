-- Menu highlight: blink the POS card / sidebar link and list POS first. On by default.
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS highlight_pos_enabled BOOLEAN NOT NULL DEFAULT TRUE;
