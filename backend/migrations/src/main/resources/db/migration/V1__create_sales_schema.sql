-- V1: Sales schema — owned by SIS
-- No cross-schema FK references. No cross-schema joins.

CREATE SCHEMA IF NOT EXISTS sales;

CREATE TABLE IF NOT EXISTS sales.sales_events (
    transaction_id   UUID          NOT NULL,
    event_date       DATE          NOT NULL,
    store_id         VARCHAR(50)   NOT NULL,
    sku_id           VARCHAR(50)   NOT NULL,
    dc_id            VARCHAR(50)   NOT NULL,
    quantity         INTEGER       NOT NULL CHECK (quantity > 0),
    unit_price       NUMERIC(10,2) NOT NULL CHECK (unit_price >= 0),
    channel          VARCHAR(20)   NOT NULL CHECK (channel IN ('POS', 'ECOMMERCE')),
    event_timestamp  TIMESTAMPTZ   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT sales_events_pk PRIMARY KEY (transaction_id, event_date)
) PARTITION BY RANGE (event_date);

-- Prototype partition: covers 30 days back and 60 days forward from migration time.
-- For production: automate partition creation via pg_partman.
CREATE TABLE IF NOT EXISTS sales.sales_events_current
    PARTITION OF sales.sales_events
    FOR VALUES FROM (CURRENT_DATE - INTERVAL '30 days')
    TO (CURRENT_DATE + INTERVAL '60 days');

CREATE INDEX IF NOT EXISTS idx_sales_sku_dc_date
    ON sales.sales_events (sku_id, dc_id, event_date);

CREATE INDEX IF NOT EXISTS idx_sales_store_date
    ON sales.sales_events (store_id, event_date);

COMMENT ON TABLE sales.sales_events IS
    'Partitioned by event_date. Owned exclusively by SIS. No other service may write here.';

-- ── Idempotency keys (SIS deduplication) ─────────────────────────────────────
-- Dedup check and sales_events INSERT are in the same @Transactional boundary.
-- Firehose retries deliver duplicates that are silently skipped via this table.
CREATE TABLE IF NOT EXISTS sales.idempotency_keys (
    event_id    VARCHAR(64)  PRIMARY KEY,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_idempotency_received_at
    ON sales.idempotency_keys (received_at);

COMMENT ON TABLE sales.idempotency_keys IS
    'SIS deduplication — 48h rolling window. Cleaned hourly by IdempotencyCleanupJob.';
