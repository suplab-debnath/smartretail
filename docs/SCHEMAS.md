# Database Schemas
 
All schemas live in the single PostgreSQL database named `smartretail`.
Flyway migrations in `migrations/flyway/` create and seed all schemas.
 
## Schema Ownership Rules
 
- Each schema is owned by exactly one ECS service.
- No service may write to another service's schema.
- No SQL JOINs across schema boundaries.
- ARS reads across schemas using separate queries, never cross-schema joins.
- The RDS schema user per service only has permissions on its own schema
  (plus ARS readonly user has SELECT on all schemas).
 
## RDS Users
 
Create these PostgreSQL users in the database:
 
```sql
CREATE USER sis_user WITH PASSWORD 'managed-by-rds-proxy-iam';
CREATE USER ims_user WITH PASSWORD 'managed-by-rds-proxy-iam';
CREATE USER re_user  WITH PASSWORD 'managed-by-rds-proxy-iam';
CREATE USER ars_readonly WITH PASSWORD 'managed-by-rds-proxy-iam';
 
GRANT USAGE ON SCHEMA sales         TO sis_user;
GRANT ALL   ON ALL TABLES IN SCHEMA sales         TO sis_user;
 
GRANT USAGE ON SCHEMA inventory     TO ims_user;
GRANT ALL   ON ALL TABLES IN SCHEMA inventory     TO ims_user;
 
GRANT USAGE ON SCHEMA replenishment TO re_user;
GRANT ALL   ON ALL TABLES IN SCHEMA replenishment TO re_user;
 
GRANT USAGE ON SCHEMA sales, forecasting, inventory,
                      replenishment, supplier, promotions TO ars_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA sales         TO ars_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA forecasting   TO ars_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA inventory     TO ars_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA replenishment TO ars_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA supplier      TO ars_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA promotions    TO ars_readonly;
```
 
---
 
## Schema 1: sales (owned by SIS)
 
```sql
-- V1__create_sales_schema.sql
 
CREATE SCHEMA IF NOT EXISTS sales;
 
CREATE TABLE sales.sales_events (
    transaction_id   UUID          NOT NULL,
    event_date       DATE          NOT NULL,
    store_id         VARCHAR(50)   NOT NULL,
    sku_id           VARCHAR(50)   NOT NULL,
    dc_id            VARCHAR(50)   NOT NULL,
    quantity         INTEGER       NOT NULL CHECK (quantity > 0),
    unit_price       NUMERIC(10,2) NOT NULL CHECK (unit_price >= 0),
    channel          VARCHAR(20)   NOT NULL CHECK (channel IN ('POS', 'ECOMMERCE')),
    event_timestamp  TIMESTAMPTZ   NOT NULL,
    raw_s3_reference VARCHAR(500),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT sales_events_pk PRIMARY KEY (transaction_id, event_date)
) PARTITION BY RANGE (event_date);
 
-- Create partitions for prototype (current month + next month)
CREATE TABLE sales.sales_events_current
    PARTITION OF sales.sales_events
    FOR VALUES FROM (CURRENT_DATE - INTERVAL '30 days')
    TO (CURRENT_DATE + INTERVAL '60 days');
 
CREATE INDEX idx_sales_sku_dc_date
    ON sales.sales_events (sku_id, dc_id, event_date);
 
CREATE INDEX idx_sales_store_date
    ON sales.sales_events (store_id, event_date);
```
 
---
 
## Schema 2: forecasting (owned by DFS)
 
```sql
-- V2__create_forecasting_schema.sql
 
CREATE SCHEMA IF NOT EXISTS forecasting;
 
CREATE TABLE forecasting.forecast_runs (
    run_id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    triggered_by       VARCHAR(20)  NOT NULL CHECK (triggered_by IN ('SCHEDULED', 'MANUAL')),
    status             VARCHAR(30)  NOT NULL CHECK (status IN (
                           'TRIGGERED','TRAINING','EVALUATING',
                           'TRANSFORMING','COMPLETED','FAILED')),
    mape               NUMERIC(6,4),
    model_s3_path      VARCHAR(500),
    training_job_name  VARCHAR(200),
    transform_job_name VARCHAR(200),
    started_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at       TIMESTAMPTZ,
    CONSTRAINT forecast_runs_pk PRIMARY KEY (run_id)
);
 
CREATE TABLE forecasting.demand_forecasts (
    forecast_id    UUID          NOT NULL DEFAULT gen_random_uuid(),
    run_id         UUID          NOT NULL REFERENCES forecasting.forecast_runs(run_id),
    sku_id         VARCHAR(50)   NOT NULL,
    dc_id          VARCHAR(50)   NOT NULL,
    forecast_date  DATE          NOT NULL,
    horizon_days   INTEGER       NOT NULL CHECK (horizon_days > 0),
    p10            INTEGER       NOT NULL CHECK (p10 >= 0),
    p50            INTEGER       NOT NULL CHECK (p50 >= 0),
    p90            INTEGER       NOT NULL CHECK (p90 >= 0),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT demand_forecasts_pk PRIMARY KEY (forecast_id),
    CONSTRAINT demand_forecasts_unique UNIQUE (run_id, sku_id, dc_id, forecast_date, horizon_days)
);
 
CREATE INDEX idx_forecast_sku_dc_date
    ON forecasting.demand_forecasts (sku_id, dc_id, forecast_date);
 
CREATE INDEX idx_forecast_run_id
    ON forecasting.demand_forecasts (run_id);
 
COMMENT ON TABLE forecasting.demand_forecasts IS
    'Populated by Post-Processor Lambda via DFS ECS inbound port (ForecastWritePort)';
```
 
---
 
## Schema 3: inventory (owned by IMS)
 
```sql
-- V3__create_inventory_schema.sql
 
CREATE SCHEMA IF NOT EXISTS inventory;
 
CREATE TABLE inventory.inventory_positions (
    position_id    UUID         NOT NULL DEFAULT gen_random_uuid(),
    sku_id         VARCHAR(50)  NOT NULL,
    dc_id          VARCHAR(50)  NOT NULL,
    on_hand        INTEGER      NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
    in_transit     INTEGER      NOT NULL DEFAULT 0 CHECK (in_transit >= 0),
    reserved       INTEGER      NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    reorder_point  INTEGER      NOT NULL DEFAULT 0,
    safety_stock   INTEGER      NOT NULL DEFAULT 0,
    version        INTEGER      NOT NULL DEFAULT 0,
    last_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT inventory_positions_pk PRIMARY KEY (position_id),
    CONSTRAINT inventory_positions_unique UNIQUE (sku_id, dc_id)
);
 
CREATE TABLE inventory.stock_alerts (
    alert_id       UUID         NOT NULL DEFAULT gen_random_uuid(),
    position_id    UUID         NOT NULL REFERENCES inventory.inventory_positions(position_id),
    alert_type     VARCHAR(20)  NOT NULL CHECK (alert_type IN ('LOW_STOCK', 'OVERSTOCK')),
    severity       VARCHAR(10)  NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM')),
    threshold_value INTEGER     NOT NULL,
    actual_value   INTEGER      NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                                CHECK (status IN ('ACTIVE', 'RESOLVED')),
    raised_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at    TIMESTAMPTZ,
    CONSTRAINT stock_alerts_pk PRIMARY KEY (alert_id)
);
 
CREATE INDEX idx_inventory_dc_id
    ON inventory.inventory_positions (dc_id);
 
CREATE INDEX idx_stock_alerts_status
    ON inventory.stock_alerts (status, raised_at DESC);
 
CREATE INDEX idx_stock_alerts_position
    ON inventory.stock_alerts (position_id);
```
 
---
 
## Schema 4: replenishment (owned by RE)
 
```sql
-- V4__create_replenishment_schema.sql
 
CREATE SCHEMA IF NOT EXISTS replenishment;
 
CREATE TABLE replenishment.replenishment_rules (
    rule_id               UUID           NOT NULL DEFAULT gen_random_uuid(),
    supplier_id           VARCHAR(50)    NOT NULL,
    sku_id                VARCHAR(50)    NOT NULL,
    dc_id                 VARCHAR(50)    NOT NULL,
    lead_time_days        INTEGER        NOT NULL CHECK (lead_time_days > 0),
    moq                   INTEGER        NOT NULL CHECK (moq > 0),
    cost_per_unit         NUMERIC(10,2)  NOT NULL CHECK (cost_per_unit > 0),
    auto_approve_threshold NUMERIC(12,2) NOT NULL CHECK (auto_approve_threshold >= 0),
    active                BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT replenishment_rules_pk PRIMARY KEY (rule_id),
    CONSTRAINT replenishment_rules_unique UNIQUE (supplier_id, sku_id, dc_id)
);
 
CREATE TABLE replenishment.purchase_orders (
    po_id             UUID          NOT NULL DEFAULT gen_random_uuid(),
    rule_id           UUID          NOT NULL REFERENCES replenishment.replenishment_rules(rule_id),
    supplier_id       VARCHAR(50)   NOT NULL,
    sku_id            VARCHAR(50)   NOT NULL,
    dc_id             VARCHAR(50)   NOT NULL,
    quantity          INTEGER       NOT NULL CHECK (quantity > 0),
    total_value       NUMERIC(12,2) NOT NULL CHECK (total_value > 0),
    workflow_status   VARCHAR(30)   NOT NULL DEFAULT 'DRAFT'
                      CHECK (workflow_status IN (
                          'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED',
                          'EXPIRED', 'DISPATCHED', 'ACKNOWLEDGED',
                          'SHIPPED', 'PARTIAL_DELIVERY', 'COMPLETED', 'CANCELLED'
                      )),
    version           INTEGER       NOT NULL DEFAULT 0,
    approved_by       VARCHAR(100),
    approved_at       TIMESTAMPTZ,
    rejected_by       VARCHAR(100),
    rejected_at       TIMESTAMPTZ,
    rejection_reason  TEXT,
    alert_id          UUID,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT purchase_orders_pk PRIMARY KEY (po_id)
);
 
CREATE TABLE replenishment.po_line_items (
    line_id     UUID          NOT NULL DEFAULT gen_random_uuid(),
    po_id       UUID          NOT NULL REFERENCES replenishment.purchase_orders(po_id),
    sku_id      VARCHAR(50)   NOT NULL,
    quantity    INTEGER       NOT NULL CHECK (quantity > 0),
    unit_cost   NUMERIC(10,2) NOT NULL CHECK (unit_cost > 0),
    line_total  NUMERIC(12,2) NOT NULL,
    CONSTRAINT po_line_items_pk PRIMARY KEY (line_id)
);
 
CREATE INDEX idx_po_workflow_status
    ON replenishment.purchase_orders (workflow_status, created_at DESC);
 
CREATE INDEX idx_po_supplier
    ON replenishment.purchase_orders (supplier_id, workflow_status);
 
CREATE INDEX idx_po_sku_dc
    ON replenishment.purchase_orders (sku_id, dc_id, workflow_status);
 
COMMENT ON COLUMN replenishment.purchase_orders.version IS
    'Optimistic locking — increment on every UPDATE. Always include WHERE version = :v in updates.';
 
COMMENT ON COLUMN replenishment.purchase_orders.workflow_status IS
    'DB-backed state machine. Valid transitions:
     DRAFT → APPROVED (auto-approve) | PENDING_APPROVAL (manual)
     PENDING_APPROVAL → APPROVED | REJECTED | EXPIRED
     APPROVED → DISPATCHED
     DISPATCHED → ACKNOWLEDGED | OVERDUE
     ACKNOWLEDGED → SHIPPED | EXCEPTION_RAISED
     SHIPPED → COMPLETED | PARTIAL_DELIVERY
     PARTIAL_DELIVERY → COMPLETED
     OVERDUE → CANCELLED';
```
 
---
 
## Schema 5: supplier (owned by SUP — stub in prototype)
 
```sql
-- V5__create_supplier_schema.sql
 
CREATE SCHEMA IF NOT EXISTS supplier;
 
CREATE TABLE supplier.supplier_records (
    supplier_id        UUID         NOT NULL DEFAULT gen_random_uuid(),
    supplier_name      VARCHAR(200) NOT NULL,
    contact_email_enc  BYTEA,
    contact_phone_enc  BYTEA,
    address            VARCHAR(500),
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                                   CHECK (status IN ('ACTIVE', 'INACTIVE')),
    onboarded_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT supplier_records_pk PRIMARY KEY (supplier_id)
);
 
COMMENT ON COLUMN supplier.supplier_records.contact_email_enc IS
    'KMS-encrypted at application layer. Decrypted only within SUP task boundary.';
 
CREATE TABLE supplier.supplier_pos (
    supplier_po_id  UUID         NOT NULL DEFAULT gen_random_uuid(),
    supplier_id     UUID         NOT NULL REFERENCES supplier.supplier_records(supplier_id),
    po_id           VARCHAR(50)  NOT NULL,
    po_status       VARCHAR(30)  NOT NULL,
    confirmed_at    TIMESTAMPTZ,
    dispatched_at   TIMESTAMPTZ,
    eta             DATE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT supplier_pos_pk PRIMARY KEY (supplier_po_id)
);
 
COMMENT ON COLUMN supplier.supplier_pos.po_id IS
    'Logical reference to replenishment.purchase_orders.po_id.
     NOT a foreign key — cross-schema FK constraints are forbidden.
     Relationship resolved via purchase order domain event.';
 
CREATE TABLE supplier.shipment_updates (
    update_id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    supplier_po_id     UUID        NOT NULL REFERENCES supplier.supplier_pos(supplier_po_id),
    update_type        VARCHAR(20) NOT NULL
                       CHECK (update_type IN ('ACKNOWLEDGED','SHIPPED','ETA_UPDATE','EXCEPTION')),
    shipment_reference VARCHAR(200),
    actual_qty_shipped INTEGER,
    revised_eta        DATE,
    exception_type     VARCHAR(50),
    notes              TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT shipment_updates_pk PRIMARY KEY (update_id)
);
```
 
---
 
## Schema 6: promotions (owned by PPS — reference data only)
 
```sql
-- V6__create_promotions_schema.sql
 
CREATE SCHEMA IF NOT EXISTS promotions;
 
CREATE TABLE promotions.promotion_schedules (
    promotion_id     UUID           NOT NULL DEFAULT gen_random_uuid(),
    promotion_name   VARCHAR(200)   NOT NULL,
    sku_ids          UUID[]         NOT NULL,
    dc_ids           VARCHAR(50)[],
    discount_pct     NUMERIC(5,2)   NOT NULL CHECK (discount_pct > 0 AND discount_pct <= 100),
    uplift_factor    NUMERIC(6,4)   NOT NULL CHECK (uplift_factor > 0),
    elasticity_coeff NUMERIC(6,4)   NOT NULL DEFAULT 1.0,
    valid_from       TIMESTAMPTZ    NOT NULL,
    valid_to         TIMESTAMPTZ    NOT NULL,
    status           VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE'
                                   CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED')),
    source_event_id  UUID           NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT promotion_schedules_pk PRIMARY KEY (promotion_id),
    CONSTRAINT promotion_valid_range CHECK (valid_to > valid_from)
);
 
CREATE INDEX idx_promo_valid_status
    ON promotions.promotion_schedules (valid_from, valid_to, status);
 
COMMENT ON TABLE promotions.promotion_schedules IS
    'Read-only reference data. Sourced from Campaign Management System events.
     PPS ECS writes here. ARS and DFS read here.';
```
 
---
 
## DynamoDB Table: idempotency-keys (owned by SIS)
 
```
Table name:    smartretail-idempotency-keys-{env}
Billing mode:  PAY_PER_REQUEST (On-Demand)
Encryption:    AWS_OWNED_KMS
 
Attributes:
  event_id    (S)   ← SHA-256 hex of transactionId   [Partition Key]
  expires_at  (N)   ← Unix epoch seconds (TTL attribute)
 
TTL attribute name: expires_at
TTL value:          current_time_epoch + (48 * 3600)
 
No GSIs. No sort key. Key-only lookup — GetItem and PutItem only.
```
 
CDK definition:
```typescript
const idempotencyTable = new dynamodb.Table(this, 'IdempotencyKeys', {
  tableName: `smartretail-idempotency-keys-${env}`,
  partitionKey: { name: 'event_id', type: dynamodb.AttributeType.STRING },
  billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
  timeToLiveAttribute: 'expires_at',
  removalPolicy: cdk.RemovalPolicy.DESTROY, // prototype only
});
```
 
 