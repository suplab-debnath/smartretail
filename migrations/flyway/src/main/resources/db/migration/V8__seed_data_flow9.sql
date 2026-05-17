-- V8: Flow 9 supplemental seed data
-- Fixes schema gaps discovered during SC Planner Console implementation:
--   1. Add actual_units column to forecasting.demand_forecasts
--   2. Add sku_id / dc_id / quantity to supplier.supplier_pos (denormalised — no cross-schema FK)
--   3. Backfill existing supplier_pos rows from replenishment.purchase_orders (migration-only join)
--   4. Insert 30-day historical forecast bands with actual_units for the three DCs (SKU-BEV-001)
--   5. Insert 5 PENDING_APPROVAL purchase orders (surface 9.5)
--   6. Insert active supplier_pos rows with mixed statuses (surface 9.6)
--   7. Insert matching shipment_updates for new supplier_pos rows

-- ============================================================
-- 1. Schema alterations
-- ============================================================

ALTER TABLE forecasting.demand_forecasts
    ADD COLUMN IF NOT EXISTS actual_units INTEGER;

ALTER TABLE supplier.supplier_pos
    ADD COLUMN IF NOT EXISTS sku_id    VARCHAR(50),
    ADD COLUMN IF NOT EXISTS dc_id     VARCHAR(50),
    ADD COLUMN IF NOT EXISTS quantity  INTEGER;

-- ============================================================
-- 2. Backfill supplier_pos denormalised columns
--    Cross-schema join is acceptable in a Flyway migration script.
--    Application code (ARS/SUP) must never cross schema boundaries.
-- ============================================================

UPDATE supplier.supplier_pos sp
SET sku_id   = po.sku_id,
    dc_id    = po.dc_id,
    quantity = po.quantity
FROM replenishment.purchase_orders po
WHERE sp.po_id = po.po_id::text;

-- ============================================================
-- 3. Historical demand forecast bands with actual_units
--    Run-id '55555555-0030-0000-0000-000000000001' is the latest
--    COMPLETED run inserted in V7 (started 1 day ago).
--    We insert past-date rows for SKU-BEV-001 across all 3 DCs
--    so the Demand Forecast chart "Actual" line has data to render.
--    actual_units = p50 × a small deterministic noise factor
--    (±7% cycling over a 7-day week pattern).
-- ============================================================

INSERT INTO forecasting.demand_forecasts
    (run_id, sku_id, dc_id, forecast_date, horizon_days, p10, p50, p90, actual_units)
SELECT
    '55555555-0030-0000-0000-000000000001',
    sku.sku_id,
    dc.dc_id,
    CURRENT_DATE - g.days_ago,
    30,
    ROUND(dc.base_p50 * 0.80)::integer,
    dc.base_p50,
    ROUND(dc.base_p50 * 1.20)::integer,
    -- deterministic ±7 % noise driven by day-of-week (0–6)
    ROUND(dc.base_p50 * (0.93 + (g.days_ago % 7) * 0.02))::integer
FROM generate_series(1, 30) AS g(days_ago)
CROSS JOIN (VALUES ('SKU-BEV-001')) AS sku(sku_id)
CROSS JOIN (
    VALUES
        ('DC-LONDON',      450),
        ('DC-MANCHESTER',  310),
        ('DC-BIRMINGHAM',  280)
) AS dc(dc_id, base_p50)
ON CONFLICT (run_id, sku_id, dc_id, forecast_date, horizon_days) DO NOTHING;

-- Same for 7-day and 14-day horizons so horizon selector is populated
INSERT INTO forecasting.demand_forecasts
    (run_id, sku_id, dc_id, forecast_date, horizon_days, p10, p50, p90, actual_units)
SELECT
    '55555555-0030-0000-0000-000000000001',
    sku.sku_id,
    dc.dc_id,
    CURRENT_DATE - g.days_ago,
    h.horizon_days,
    ROUND(dc.base_p50 * 0.80)::integer,
    dc.base_p50,
    ROUND(dc.base_p50 * 1.20)::integer,
    ROUND(dc.base_p50 * (0.93 + (g.days_ago % 7) * 0.02))::integer
FROM generate_series(1, 14) AS g(days_ago)
CROSS JOIN (VALUES ('SKU-BEV-001')) AS sku(sku_id)
CROSS JOIN (
    VALUES
        ('DC-LONDON',      450),
        ('DC-MANCHESTER',  310),
        ('DC-BIRMINGHAM',  280)
) AS dc(dc_id, base_p50)
CROSS JOIN (VALUES (7), (14)) AS h(horizon_days)
WHERE g.days_ago <= h.horizon_days
ON CONFLICT (run_id, sku_id, dc_id, forecast_date, horizon_days) DO NOTHING;

-- ============================================================
-- 4. PENDING_APPROVAL purchase orders  (surface 9.5)
--    5 POs spanning all 5 suppliers — version = 1 (not yet approved)
--    UUID pattern: aaaaaaaa-00XX-0000-0000-000000000001
-- ============================================================

INSERT INTO replenishment.purchase_orders
    (po_id, rule_id, supplier_id, sku_id, dc_id, quantity, total_value,
     workflow_status, version, approved_by, approved_at, updated_at)
VALUES
    -- Acme Beverages — SKU-BEV-001 / DC-LONDON
    ('aaaaaaaa-0001-0000-0000-000000000001',
     '44444444-0001-0000-0000-000000000001',
     '11111111-0000-0000-0000-000000000001',
     'SKU-BEV-001', 'DC-LONDON', 700, 5950.00,
     'PENDING_APPROVAL', 1, NULL, NULL, NOW()),

    -- Premier Snacks — SKU-SNK-001 / DC-MANCHESTER
    ('aaaaaaaa-0002-0000-0000-000000000001',
     '44444444-0006-0000-0000-000000000001',
     '11111111-0000-0000-0000-000000000002',
     'SKU-SNK-001', 'DC-MANCHESTER', 250, 2750.00,
     'PENDING_APPROVAL', 1, NULL, NULL, NOW()),

    -- Dry Goods Wholesale — SKU-DRY-001 / DC-BIRMINGHAM
    ('aaaaaaaa-0003-0000-0000-000000000001',
     '44444444-0009-0000-0000-000000000001',
     '11111111-0000-0000-0000-000000000003',
     'SKU-DRY-001', 'DC-BIRMINGHAM', 600, 2520.00,
     'PENDING_APPROVAL', 1, NULL, NULL, NOW()),

    -- Chill Chain Logistics — SKU-CHL-001 / DC-LONDON
    ('aaaaaaaa-0004-0000-0000-000000000001',
     '44444444-0011-0000-0000-000000000001',
     '11111111-0000-0000-0000-000000000004',
     'SKU-CHL-001', 'DC-LONDON', 350, 2415.00,
     'PENDING_APPROVAL', 1, NULL, NULL, NOW()),

    -- Metro Food Distributors — SKU-BEV-005 / DC-BIRMINGHAM
    ('aaaaaaaa-0005-0000-0000-000000000001',
     '44444444-0005-0000-0000-000000000001',
     '11111111-0000-0000-0000-000000000005',
     'SKU-BEV-005', 'DC-BIRMINGHAM', 500, 2600.00,
     'PENDING_APPROVAL', 1, NULL, NULL, NOW())
ON CONFLICT DO NOTHING;

-- ============================================================
-- 5. Active supplier_pos rows — mixed statuses for surface 9.6
--    UUID pattern: bbbbbbbb-00XX-0000-0000-000000000001
--    Statuses used: PENDING / CONFIRMED / DISPATCHED / EXCEPTION
--    These reference the new PENDING_APPROVAL POs above.
-- ============================================================

INSERT INTO supplier.supplier_pos
    (supplier_po_id, supplier_id, po_id, po_status,
     sku_id, dc_id, quantity,
     confirmed_at, dispatched_at, eta)
VALUES
    -- PO1: Acme — CONFIRMED (acknowledged, not dispatched yet)
    ('bbbbbbbb-0001-0000-0000-000000000001',
     '11111111-0000-0000-0000-000000000001',
     'aaaaaaaa-0001-0000-0000-000000000001',
     'CONFIRMED',
     'SKU-BEV-001', 'DC-LONDON', 700,
     NOW() - INTERVAL '2 days', NULL, CURRENT_DATE + 5),

    -- PO2: Premier — DISPATCHED (in transit, ETA in 4 days)
    ('bbbbbbbb-0002-0000-0000-000000000001',
     '11111111-0000-0000-0000-000000000002',
     'aaaaaaaa-0002-0000-0000-000000000001',
     'DISPATCHED',
     'SKU-SNK-001', 'DC-MANCHESTER', 250,
     NOW() - INTERVAL '4 days', NOW() - INTERVAL '1 day', CURRENT_DATE + 4),

    -- PO3: Dry Goods — EXCEPTION (flagged — driver shortage)
    ('bbbbbbbb-0003-0000-0000-000000000001',
     '11111111-0000-0000-0000-000000000003',
     'aaaaaaaa-0003-0000-0000-000000000001',
     'EXCEPTION',
     'SKU-DRY-001', 'DC-BIRMINGHAM', 600,
     NOW() - INTERVAL '5 days', NULL, NULL),

    -- PO4: Chill Chain — PENDING (just raised, not yet acknowledged)
    ('bbbbbbbb-0004-0000-0000-000000000001',
     '11111111-0000-0000-0000-000000000004',
     'aaaaaaaa-0004-0000-0000-000000000001',
     'PENDING',
     'SKU-CHL-001', 'DC-LONDON', 350,
     NULL, NULL, CURRENT_DATE + 7),

    -- PO5: Metro — CONFIRMED (slow to dispatch — at-risk)
    ('bbbbbbbb-0005-0000-0000-000000000001',
     '11111111-0000-0000-0000-000000000005',
     'aaaaaaaa-0005-0000-0000-000000000001',
     'CONFIRMED',
     'SKU-BEV-005', 'DC-BIRMINGHAM', 500,
     NOW() - INTERVAL '3 days', NULL, CURRENT_DATE + 8)
ON CONFLICT DO NOTHING;

-- ============================================================
-- 6. shipment_updates for new supplier_pos
--    ACKNOWLEDGED for CONFIRMED/EXCEPTION rows
--    ACKNOWLEDGED + SHIPPED for DISPATCHED row
--    EXCEPTION update for the flagged row
-- ============================================================

INSERT INTO supplier.shipment_updates
    (supplier_po_id, update_type, actual_qty_shipped, exception_type, notes, created_at)
VALUES
    -- PO1 (CONFIRMED): acknowledged
    ('bbbbbbbb-0001-0000-0000-000000000001',
     'ACKNOWLEDGED', NULL, NULL,
     'Order received and confirmed', NOW() - INTERVAL '2 days'),

    -- PO2 (DISPATCHED): acknowledged then shipped
    ('bbbbbbbb-0002-0000-0000-000000000001',
     'ACKNOWLEDGED', NULL, NULL,
     'Order received and confirmed', NOW() - INTERVAL '4 days'),
    ('bbbbbbbb-0002-0000-0000-000000000001',
     'SHIPPED', 250, NULL,
     'Dispatched from Premier Snacks DC-North', NOW() - INTERVAL '1 day'),

    -- PO3 (EXCEPTION): acknowledged then flagged
    ('bbbbbbbb-0003-0000-0000-000000000001',
     'ACKNOWLEDGED', NULL, NULL,
     'Order received and confirmed', NOW() - INTERVAL '5 days'),
    ('bbbbbbbb-0003-0000-0000-000000000001',
     'EXCEPTION', NULL, 'DRIVER_SHORTAGE',
     'Regional driver shortage — dispatch delayed by 3–5 days', NOW() - INTERVAL '1 day'),

    -- PO5 (CONFIRMED): acknowledged
    ('bbbbbbbb-0005-0000-0000-000000000001',
     'ACKNOWLEDGED', NULL, NULL,
     'Order received and confirmed', NOW() - INTERVAL '3 days')
ON CONFLICT DO NOTHING;
