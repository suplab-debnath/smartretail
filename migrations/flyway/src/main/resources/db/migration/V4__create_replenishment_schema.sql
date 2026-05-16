-- V4: Replenishment schema — owned by RE (Replenishment Engine)
-- purchase_orders uses a DB-backed state machine. No Step Functions.
-- All UPDATEs require WHERE version = :currentVersion (optimistic locking).

CREATE SCHEMA IF NOT EXISTS replenishment;

CREATE TABLE IF NOT EXISTS replenishment.replenishment_rules (
    rule_id                UUID           NOT NULL DEFAULT gen_random_uuid(),
    supplier_id            VARCHAR(50)    NOT NULL,
    sku_id                 VARCHAR(50)    NOT NULL,
    dc_id                  VARCHAR(50)    NOT NULL,
    lead_time_days         INTEGER        NOT NULL CHECK (lead_time_days > 0),
    moq                    INTEGER        NOT NULL CHECK (moq > 0),
    cost_per_unit          NUMERIC(10,2)  NOT NULL CHECK (cost_per_unit > 0),
    auto_approve_threshold NUMERIC(12,2)  NOT NULL CHECK (auto_approve_threshold >= 0),
    active                 BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT replenishment_rules_pk PRIMARY KEY (rule_id),
    CONSTRAINT replenishment_rules_unique UNIQUE (supplier_id, sku_id, dc_id)
);

CREATE TABLE IF NOT EXISTS replenishment.purchase_orders (
    po_id            UUID          NOT NULL DEFAULT gen_random_uuid(),
    rule_id          UUID          NOT NULL REFERENCES replenishment.replenishment_rules(rule_id),
    supplier_id      VARCHAR(50)   NOT NULL,
    sku_id           VARCHAR(50)   NOT NULL,
    dc_id            VARCHAR(50)   NOT NULL,
    quantity         INTEGER       NOT NULL CHECK (quantity > 0),
    total_value      NUMERIC(12,2) NOT NULL CHECK (total_value > 0),
    workflow_status  VARCHAR(30)   NOT NULL DEFAULT 'DRAFT'
                     CHECK (workflow_status IN (
                         'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED',
                         'EXPIRED', 'DISPATCHED', 'ACKNOWLEDGED',
                         'SHIPPED', 'PARTIAL_DELIVERY', 'COMPLETED', 'CANCELLED'
                     )),
    version          INTEGER       NOT NULL DEFAULT 0,
    approved_by      VARCHAR(100),
    approved_at      TIMESTAMPTZ,
    rejected_by      VARCHAR(100),
    rejected_at      TIMESTAMPTZ,
    rejection_reason TEXT,
    alert_id         UUID,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT purchase_orders_pk PRIMARY KEY (po_id)
);

CREATE TABLE IF NOT EXISTS replenishment.po_line_items (
    line_id    UUID          NOT NULL DEFAULT gen_random_uuid(),
    po_id      UUID          NOT NULL REFERENCES replenishment.purchase_orders(po_id),
    sku_id     VARCHAR(50)   NOT NULL,
    quantity   INTEGER       NOT NULL CHECK (quantity > 0),
    unit_cost  NUMERIC(10,2) NOT NULL CHECK (unit_cost > 0),
    line_total NUMERIC(12,2) NOT NULL,
    CONSTRAINT po_line_items_pk PRIMARY KEY (line_id)
);

CREATE INDEX IF NOT EXISTS idx_po_workflow_status
    ON replenishment.purchase_orders (workflow_status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_po_supplier
    ON replenishment.purchase_orders (supplier_id, workflow_status);

CREATE INDEX IF NOT EXISTS idx_po_sku_dc
    ON replenishment.purchase_orders (sku_id, dc_id, workflow_status);

COMMENT ON COLUMN replenishment.purchase_orders.version IS
    'Optimistic locking — increment on every UPDATE. Always include WHERE version = :v in updates.';

COMMENT ON COLUMN replenishment.purchase_orders.workflow_status IS
    'DB-backed state machine. Valid transitions:
     DRAFT → APPROVED (auto-approve) | PENDING_APPROVAL (manual)
     PENDING_APPROVAL → APPROVED | REJECTED | EXPIRED
     APPROVED → DISPATCHED → ACKNOWLEDGED → SHIPPED → COMPLETED | PARTIAL_DELIVERY';

COMMENT ON COLUMN replenishment.purchase_orders.alert_id IS
    'Logical reference to inventory.stock_alerts.alert_id.
     NOT a foreign key — cross-schema FK constraints are forbidden.
     Relationship resolved via InventoryAlertEvent domain event.';
