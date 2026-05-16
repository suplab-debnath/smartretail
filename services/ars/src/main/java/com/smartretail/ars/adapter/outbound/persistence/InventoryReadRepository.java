package com.smartretail.ars.adapter.outbound.persistence;

import com.smartretail.ars.domain.model.ExecutiveDashboard.StockoutDataPoint;
import com.smartretail.ars.port.outbound.InventoryReadPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

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
}
