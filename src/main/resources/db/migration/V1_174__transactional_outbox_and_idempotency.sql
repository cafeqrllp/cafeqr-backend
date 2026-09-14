-- ===========================================================================
-- V1_171: Transactional Outbox & Idempotency Infrastructure
--
-- 1. outbox_events — transactional outbox for reliable async event delivery
-- 2. processed_events — consumer-side deduplication / idempotency tracking
-- 3. stock_ledgers idempotency index — prevents duplicate inventory operations
-- ===========================================================================

-- ═══════════════════════════════════════════════════════════════════════════
-- 1. outbox_events — Transactional Outbox Table
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    org_id UUID,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT
);

-- Index for the outbox processor to claim pending events efficiently.
-- Covers: WHERE status = 'PENDING' AND available_at <= NOW() ORDER BY created_at
CREATE INDEX IF NOT EXISTS idx_outbox_pending
ON outbox_events (status, available_at, created_at);

-- Covering index for archival/cleanup queries
CREATE INDEX IF NOT EXISTS idx_outbox_completed_cleanup
ON outbox_events (status, processed_at)
WHERE status = 'COMPLETED';

-- ═══════════════════════════════════════════════════════════════════════════
-- 2. processed_events — Consumer-Side Idempotency Table
-- ═══════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS processed_events (
    consumer_name VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, event_id)
);

-- ═══════════════════════════════════════════════════════════════════════════
-- 3. stock_ledgers — Index for Sale Deductions Lookup
-- ═══════════════════════════════════════════════════════════════════════════
-- Supports fast lookup for stock deductions and reversals.
-- Non-unique because an order can contain multiple lines with the same product
-- or multiple menu items sharing the same recipe ingredient.
CREATE INDEX IF NOT EXISTS idx_stock_ledger_sale_deduction_idempotency
ON stock_ledgers (
    reference_id,
    product_id,
    COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid),
    transaction_type
)
WHERE reference_id IS NOT NULL
  AND transaction_type IN ('SALE_DEDUCTION', 'SALE_REVERSAL');
