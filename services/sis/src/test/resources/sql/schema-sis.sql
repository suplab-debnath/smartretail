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
    raw_s3_reference VARCHAR(500),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (transaction_id, event_date)
);
