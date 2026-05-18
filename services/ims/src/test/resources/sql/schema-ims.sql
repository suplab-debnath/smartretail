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
