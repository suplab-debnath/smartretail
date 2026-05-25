-- V2: Forecasting schema — owned by DFS (Demand Forecast Service)
-- Not exercised by Flows 1–4. Populated by seed data for Flows 8 (MAPE trend).

CREATE SCHEMA IF NOT EXISTS forecasting;

CREATE TABLE IF NOT EXISTS forecasting.forecast_runs (
    run_id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    triggered_by       VARCHAR(20)  NOT NULL CHECK (triggered_by IN ('SCHEDULED', 'MANUAL')),
    status             VARCHAR(30)  NOT NULL CHECK (status IN (
                           'TRIGGERED', 'TRAINING', 'EVALUATING',
                           'TRANSFORMING', 'COMPLETED', 'FAILED')),
    mape               NUMERIC(6,4),
    model_s3_path      VARCHAR(500),
    training_job_name  VARCHAR(200),
    transform_job_name VARCHAR(200),
    started_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at       TIMESTAMPTZ,
    CONSTRAINT forecast_runs_pk PRIMARY KEY (run_id)
);

CREATE TABLE IF NOT EXISTS forecasting.demand_forecasts (
    forecast_id    UUID          NOT NULL DEFAULT gen_random_uuid(),
    run_id         UUID          NOT NULL REFERENCES forecasting.forecast_runs(run_id),
    sku_id         VARCHAR(50)   NOT NULL,
    dc_id          VARCHAR(50)   NOT NULL,
    forecast_date  DATE          NOT NULL,
    horizon_days   INTEGER       NOT NULL CHECK (horizon_days > 0),
    p10            INTEGER       NOT NULL CHECK (p10 >= 0),
    p50            INTEGER       NOT NULL CHECK (p50 >= 0),
    p90            INTEGER       NOT NULL CHECK (p90 >= 0),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT demand_forecasts_pk PRIMARY KEY (forecast_id),
    CONSTRAINT demand_forecasts_unique UNIQUE (run_id, sku_id, dc_id, forecast_date, horizon_days)
);

CREATE INDEX IF NOT EXISTS idx_forecast_sku_dc_date
    ON forecasting.demand_forecasts (sku_id, dc_id, forecast_date);

CREATE INDEX IF NOT EXISTS idx_forecast_run_id
    ON forecasting.demand_forecasts (run_id);

COMMENT ON TABLE forecasting.demand_forecasts IS
    'Populated by Post-Processor Lambda via DFS ECS inbound port (ForecastWritePort).';
