CREATE SCHEMA IF NOT EXISTS replenishment;

CREATE TABLE IF NOT EXISTS replenishment.replenishment_rules (
    rule_id                UUID           NOT NULL DEFAULT gen_random_uuid(),
    supplier_id            VARCHAR(50)    NOT NULL,
    sku_id                 VARCHAR(50)    NOT NULL,
    dc_id                  VARCHAR(50)    NOT NULL,
    lead_time_days         INTEGER        NOT NULL,
    moq                    INTEGER        NOT NULL,
    cost_per_unit          NUMERIC(10,2)  NOT NULL,
    auto_approve_threshold NUMERIC(12,2)  NOT NULL,
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
    quantity         INTEGER       NOT NULL,
    total_value      NUMERIC(12,2) NOT NULL,
    workflow_status  VARCHAR(30)   NOT NULL DEFAULT 'DRAFT',
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
    quantity   INTEGER       NOT NULL,
    unit_cost  NUMERIC(10,2) NOT NULL,
    line_total NUMERIC(12,2) NOT NULL,
    CONSTRAINT po_line_items_pk PRIMARY KEY (line_id)
);
