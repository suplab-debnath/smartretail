-- V3: Inventory schema — owned by IMS
-- version column on inventory_positions is the optimistic lock.
-- All UPDATE statements must include WHERE version = :currentVersion.

CREATE SCHEMA IF NOT EXISTS inventory;

CREATE TABLE IF NOT EXISTS inventory.inventory_positions (
    position_id     UUID         NOT NULL DEFAULT gen_random_uuid(),
    sku_id          VARCHAR(50)  NOT NULL,
    dc_id           VARCHAR(50)  NOT NULL,
    on_hand         INTEGER      NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
    in_transit      INTEGER      NOT NULL DEFAULT 0 CHECK (in_transit >= 0),
    reserved        INTEGER      NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    reorder_point   INTEGER      NOT NULL DEFAULT 0,
    safety_stock    INTEGER      NOT NULL DEFAULT 0,
    version         INTEGER      NOT NULL DEFAULT 0,
    last_updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT inventory_positions_pk PRIMARY KEY (position_id),
    CONSTRAINT inventory_positions_unique UNIQUE (sku_id, dc_id)
);

CREATE TABLE IF NOT EXISTS inventory.stock_alerts (
    alert_id        UUID         NOT NULL DEFAULT gen_random_uuid(),
    position_id     UUID         NOT NULL REFERENCES inventory.inventory_positions(position_id),
    alert_type      VARCHAR(20)  NOT NULL CHECK (alert_type IN ('LOW_STOCK', 'OVERSTOCK')),
    severity        VARCHAR(10)  NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM')),
    threshold_value INTEGER      NOT NULL,
    actual_value    INTEGER      NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                                 CHECK (status IN ('ACTIVE', 'RESOLVED')),
    raised_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,
    CONSTRAINT stock_alerts_pk PRIMARY KEY (alert_id)
);

CREATE INDEX IF NOT EXISTS idx_inventory_dc_id
    ON inventory.inventory_positions (dc_id);

-- Partial index: most queries filter by status = 'ACTIVE'
CREATE INDEX IF NOT EXISTS idx_active_alerts
    ON inventory.stock_alerts (raised_at DESC)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_stock_alerts_status
    ON inventory.stock_alerts (status, raised_at DESC);

CREATE INDEX IF NOT EXISTS idx_stock_alerts_position
    ON inventory.stock_alerts (position_id);

COMMENT ON COLUMN inventory.inventory_positions.version IS
    'Optimistic lock — increment on every UPDATE. Always include WHERE version = :v in updates. If rowsUpdated = 0, throw OptimisticLockException and retry.';

COMMENT ON COLUMN inventory.inventory_positions.on_hand IS
    'Physical units currently held in the DC. Decremented by IMS on each SalesTransactionEvent.';
