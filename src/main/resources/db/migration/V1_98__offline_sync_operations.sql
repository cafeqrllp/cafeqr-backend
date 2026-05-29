CREATE TABLE IF NOT EXISTS sync_operations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id UUID,
    org_id UUID,
    terminal_id UUID,
    operation_id VARCHAR(160) NOT NULL,
    client_request_id VARCHAR(160),
    offline_id VARCHAR(160),
    method VARCHAR(12) NOT NULL,
    url TEXT,
    entity VARCHAR(80),
    status VARCHAR(40) NOT NULL,
    error_message TEXT,
    payload_json JSONB,
    response_json JSONB,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_sync_operations_client_operation UNIQUE (client_id, operation_id)
);

CREATE INDEX IF NOT EXISTS idx_sync_operations_client_org
    ON sync_operations (client_id, org_id);

CREATE INDEX IF NOT EXISTS idx_sync_operations_updated_at
    ON sync_operations (updated_at);
