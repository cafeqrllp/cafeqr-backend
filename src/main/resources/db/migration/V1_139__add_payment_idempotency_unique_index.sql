-- Flyway migration V1_139: Add unique index on (client_id, source_operation_id) for payment idempotency
CREATE UNIQUE INDEX IF NOT EXISTS idx_payments_client_source_op_id
ON payments (client_id, source_operation_id)
WHERE source_operation_id IS NOT NULL AND source_operation_id != '';
