-- Settings > "Enable MCP": turns on the read-only Ask CafeQR assistant / MCP access. Off by default.
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS mcp_enabled BOOLEAN NOT NULL DEFAULT FALSE;
