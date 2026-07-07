-- V1_124: Add partial unique index on orders.source_local_ref per tenant
-- This enables idempotent order creation: a retry carrying the same sourceLocalRef
-- is detected and the original order is returned instead of creating a duplicate.
--
-- The WHERE clause excludes VOID rows because updateOrder() copies sourceLocalRef
-- from the old order to the new revision and then stamps the old row as VOID.
-- Without the exclusion the unique constraint would block order edits.

ALTER TABLE orders ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_client_source_local_ref
    ON orders (client_id, source_local_ref)
    WHERE source_local_ref IS NOT NULL
      AND order_status != 'VOID';
