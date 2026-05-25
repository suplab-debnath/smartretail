-- V6: Promotions schema — owned by PPS (read-only reference data in prototype)
-- ARS and DFS read this schema. PPS ECS writes here.

CREATE SCHEMA IF NOT EXISTS promotions;

CREATE TABLE IF NOT EXISTS promotions.promotion_schedules (
    promotion_id     UUID           NOT NULL DEFAULT gen_random_uuid(),
    promotion_name   VARCHAR(200)   NOT NULL,
    sku_ids          UUID[]         NOT NULL,
    dc_ids           VARCHAR(50)[],
    discount_pct     NUMERIC(5,2)   NOT NULL CHECK (discount_pct > 0 AND discount_pct <= 100),
    uplift_factor    NUMERIC(6,4)   NOT NULL CHECK (uplift_factor > 0),
    elasticity_coeff NUMERIC(6,4)   NOT NULL DEFAULT 1.0,
    valid_from       TIMESTAMPTZ    NOT NULL,
    valid_to         TIMESTAMPTZ    NOT NULL,
    status           VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE'
                                    CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED')),
    source_event_id  UUID           NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT promotion_schedules_pk PRIMARY KEY (promotion_id),
    CONSTRAINT promotion_valid_range CHECK (valid_to > valid_from)
);

CREATE INDEX IF NOT EXISTS idx_promo_valid_status
    ON promotions.promotion_schedules (valid_from, valid_to, status);

COMMENT ON TABLE promotions.promotion_schedules IS
    'Read-only reference data. Sourced from Campaign Management System events.
     PPS ECS writes here. ARS and DFS read here.';
