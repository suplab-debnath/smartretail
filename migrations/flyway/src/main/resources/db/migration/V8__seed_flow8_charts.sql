-- Additional CRITICAL stock alerts distributed across the last 30 days
-- Required for the Stockout Frequency chart on the Executive Dashboard (Flow 8)
-- Without this, only 1 CRITICAL alert exists (raised within the last 2 hours)

INSERT INTO inventory.stock_alerts
    (alert_id, position_id, alert_type, severity, threshold_value, actual_value, status, raised_at)
SELECT
    gen_random_uuid(),
    ip.position_id,
    'LOW_STOCK',
    'CRITICAL',
    ip.reorder_point,
    ip.reorder_point - (2 + (row_number() OVER (ORDER BY ip.sku_id, ip.dc_id) % 5))::int,
    CASE WHEN row_number() OVER (ORDER BY ip.sku_id, ip.dc_id) % 3 = 0
         THEN 'RESOLVED' ELSE 'ACTIVE' END,
    NOW() - ((3 + (row_number() OVER (ORDER BY ip.sku_id, ip.dc_id) * 2)) || ' days')::INTERVAL
FROM inventory.inventory_positions ip
WHERE ip.sku_id IN ('SKU-BEV-001', 'SKU-SNK-001', 'SKU-DRY-001', 'SKU-CHL-001')
  AND ip.dc_id  IN ('DC-LONDON', 'DC-MANCHESTER', 'DC-BIRMINGHAM')
ORDER BY ip.sku_id, ip.dc_id
LIMIT 12;
