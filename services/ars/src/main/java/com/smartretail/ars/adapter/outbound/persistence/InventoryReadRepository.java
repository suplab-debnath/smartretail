package com.smartretail.ars.adapter.outbound.persistence;

import com.smartretail.ars.domain.model.ExecutiveDashboard.StockoutDataPoint;
import com.smartretail.ars.domain.model.StoreManagerDashboard.AlertKpi;
import com.smartretail.ars.domain.model.StoreManagerDashboard.AlertSummary;
import com.smartretail.ars.port.outbound.InventoryReadPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class InventoryReadRepository implements InventoryReadPort {

    private static final String COUNT_CRITICAL_SQL = """
            SELECT COUNT(*)
            FROM inventory.stock_alerts
            WHERE severity = 'CRITICAL'
              AND raised_at >= NOW() - (:days || ' days')::INTERVAL
            """;

    private static final String DAILY_HISTORY_SQL = """
            SELECT DATE(raised_at) AS alert_date, COUNT(*)::INT AS critical_count
            FROM inventory.stock_alerts
            WHERE severity = 'CRITICAL'
              AND raised_at >= NOW() - (:days || ' days')::INTERVAL
            GROUP BY DATE(raised_at)
            ORDER BY alert_date DESC
            """;

    private static final String COUNT_ACTIVE_SQL = """
            SELECT COUNT(*) FROM inventory.stock_alerts WHERE status = 'ACTIVE'
            """;

    // ── Store Manager SQL (all confined to inventory schema) ──────────────────

    private static final String ALERT_KPI_SQL = """
            SELECT a.severity, COUNT(*)::INT AS cnt
            FROM inventory.stock_alerts a
            JOIN inventory.inventory_positions p ON a.position_id = p.position_id
            WHERE p.dc_id = :dcId AND a.status = 'ACTIVE'
            GROUP BY a.severity
            """;

    private static final String COUNT_ACTIVE_BY_DC_SQL = """
            SELECT COUNT(*)
            FROM inventory.stock_alerts a
            JOIN inventory.inventory_positions p ON a.position_id = p.position_id
            WHERE p.dc_id = :dcId AND a.status = 'ACTIVE'
            """;

    private static final String ALERTS_BY_DC_SQL = """
            SELECT a.alert_id, p.sku_id, p.dc_id, a.alert_type, a.severity,
                   p.on_hand, p.reorder_point, a.raised_at
            FROM inventory.stock_alerts a
            JOIN inventory.inventory_positions p ON a.position_id = p.position_id
            WHERE p.dc_id = :dcId AND a.status = 'ACTIVE'
            ORDER BY CASE a.severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 ELSE 3 END,
                     a.raised_at DESC
            LIMIT :size OFFSET :offset
            """;

    private static final String SUM_ON_HAND_SQL = """
            SELECT COALESCE(SUM(on_hand), 0) FROM inventory.inventory_positions
            WHERE dc_id = :dcId
            """;

    private static final String COUNT_DISTINCT_SKUS_SQL = """
            SELECT COUNT(DISTINCT sku_id) FROM inventory.inventory_positions
            WHERE dc_id = :dcId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public InventoryReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int countCriticalAlerts(int days) {
        Integer count = jdbc.queryForObject(
                COUNT_CRITICAL_SQL,
                new MapSqlParameterSource("days", days),
                Integer.class
        );
        return count != null ? count : 0;
    }

    @Override
    public List<StockoutDataPoint> findDailyCriticalAlertHistory(int days) {
        return jdbc.query(
                DAILY_HISTORY_SQL,
                new MapSqlParameterSource("days", days),
                (rs, rowNum) -> new StockoutDataPoint(
                        rs.getObject("alert_date", LocalDate.class),
                        rs.getInt("critical_count")
                )
        );
    }

    @Override
    public int countActiveAlerts() {
        Integer count = jdbc.queryForObject(COUNT_ACTIVE_SQL, new MapSqlParameterSource(), Integer.class);
        return count != null ? count : 0;
    }

    @Override
    public AlertKpi countActiveAlertsByDc(String dcId) {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query(ALERT_KPI_SQL, new MapSqlParameterSource("dcId", dcId), rs -> {
            counts.put(rs.getString("severity"), rs.getInt("cnt"));
        });
        return new AlertKpi(
                counts.getOrDefault("CRITICAL", 0),
                counts.getOrDefault("HIGH", 0),
                counts.getOrDefault("MEDIUM", 0)
        );
    }

    @Override
    public int countActiveAlertsByDcTotal(String dcId) {
        Integer count = jdbc.queryForObject(
                COUNT_ACTIVE_BY_DC_SQL,
                new MapSqlParameterSource("dcId", dcId),
                Integer.class
        );
        return count != null ? count : 0;
    }

    @Override
    public List<AlertSummary> findActiveAlertsByDc(String dcId, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("dcId", dcId)
                .addValue("size", size)
                .addValue("offset", page * size);
        return jdbc.query(ALERTS_BY_DC_SQL, params, (rs, rowNum) -> new AlertSummary(
                rs.getObject("alert_id", UUID.class),
                rs.getString("sku_id"),
                rs.getString("dc_id"),
                rs.getString("alert_type"),
                rs.getString("severity"),
                rs.getInt("on_hand"),
                rs.getInt("reorder_point"),
                rs.getObject("raised_at", java.sql.Timestamp.class).toInstant()
        ));
    }

    @Override
    public long sumOnHandByDc(String dcId) {
        Long sum = jdbc.queryForObject(
                SUM_ON_HAND_SQL,
                new MapSqlParameterSource("dcId", dcId),
                Long.class
        );
        return sum != null ? sum : 0L;
    }

    @Override
    public int countDistinctSkusByDc(String dcId) {
        Integer count = jdbc.queryForObject(
                COUNT_DISTINCT_SKUS_SQL,
                new MapSqlParameterSource("dcId", dcId),
                Integer.class
        );
        return count != null ? count : 0;
    }
}
