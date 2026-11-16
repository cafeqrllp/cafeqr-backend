-- Cash drawer: role permissions, branch configuration and an append-only event log.
-- Hardware (kick pin / pulse / target printer) is per terminal and lives on the device.

-- 1) Role permissions ---------------------------------------------------------
-- Manual "No Sale" open: off by default, granted to admin/manager style roles.
ALTER TABLE roles ADD COLUMN IF NOT EXISTS can_open_cash_drawer BOOLEAN DEFAULT FALSE;
-- Auto open on settle: on by default so cashiers keep working once the feature is enabled.
ALTER TABLE roles ADD COLUMN IF NOT EXISTS can_auto_open_cash_drawer BOOLEAN DEFAULT TRUE;
-- View the cash drawer history / audit report.
ALTER TABLE roles ADD COLUMN IF NOT EXISTS can_view_cash_drawer_history BOOLEAN DEFAULT FALSE;

UPDATE roles
   SET can_open_cash_drawer = TRUE,
       can_view_cash_drawer_history = TRUE
 WHERE UPPER(name) IN ('SUPER_ADMIN', 'ADMIN', 'MANAGER');

UPDATE roles SET can_auto_open_cash_drawer = TRUE WHERE can_auto_open_cash_drawer IS NULL;

-- 2) Branch / client configuration -----------------------------------------
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS cash_drawer_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS cash_drawer_auto_open_on_sale BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS cash_drawer_auto_open_on_order_settle BOOLEAN NOT NULL DEFAULT TRUE;
-- Comma separated tender types that open the drawer (CASH, CARD, UPI, ONLINE). MIXED opens when any split matches.
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS cash_drawer_open_tenders VARCHAR(100) NOT NULL DEFAULT 'CASH';
ALTER TABLE system_configurations ADD COLUMN IF NOT EXISTS cash_drawer_manual_reason_required BOOLEAN NOT NULL DEFAULT TRUE;

-- 3) Event log ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cash_drawer_events (
    id               UUID PRIMARY KEY,
    client_id        UUID,
    org_id           UUID,
    terminal_id      UUID,
    user_id          UUID,
    user_name        VARCHAR(255),
    role_name        VARCHAR(100),
    trigger_type     VARCHAR(30)  NOT NULL,
    reason           VARCHAR(255),
    order_id         UUID,
    order_no         VARCHAR(100),
    payment_method   VARCHAR(30),
    amount           NUMERIC(19, 4),
    printer_name     VARCHAR(255),
    transport        VARCHAR(50),
    status           VARCHAR(20)  NOT NULL,
    error_message    VARCHAR(500),
    device_event_at  TIMESTAMP,
    client_event_id  VARCHAR(100),
    device_label     VARCHAR(255),
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    created_by       VARCHAR(255),
    updated_by       VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_cash_drawer_events_client_created ON cash_drawer_events (client_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_cash_drawer_events_org_created ON cash_drawer_events (org_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_cash_drawer_events_user ON cash_drawer_events (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_cash_drawer_events_order ON cash_drawer_events (order_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_cash_drawer_events_client_event
    ON cash_drawer_events (client_id, client_event_id) WHERE client_event_id IS NOT NULL;
