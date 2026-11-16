-- Cash drawer audit: the kick is sent by the device before the event reaches the server, so an open that
-- breaks a rule (role not allowed, reason missing) must still be recorded. It is stored and flagged here
-- instead of being rejected. NULL = within policy; NOT_PERMITTED | REASON_MISSING otherwise.
ALTER TABLE cash_drawer_events ADD COLUMN IF NOT EXISTS policy_violation VARCHAR(40);

CREATE INDEX IF NOT EXISTS idx_cash_drawer_events_violation
    ON cash_drawer_events (client_id, created_at DESC) WHERE policy_violation IS NOT NULL;
