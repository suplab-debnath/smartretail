package com.smartretail.ars.adapter.outbound.persistence;

import com.smartretail.ars.domain.model.ExecutiveDashboard.CycleTimeDataPoint;
import com.smartretail.ars.port.outbound.ReplenishmentReadPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReplenishmentReadRepository implements ReplenishmentReadPort {

    private static final String AVG_CYCLE_TIME_SQL = """
            SELECT AVG(EXTRACT(EPOCH FROM (updated_at - approved_at)) / 86400.0)
            FROM replenishment.purchase_orders
            WHERE workflow_status IN ('DISPATCHED', 'COMPLETED')
              AND approved_at >= NOW() - (:days || ' days')::INTERVAL
            """;

    private static final String FILL_RATE_SQL = """
            SELECT supplier_id::UUID,
                   COUNT(*) FILTER (WHERE workflow_status = 'COMPLETED')::INT AS completed,
                   COUNT(*)::INT AS total
            FROM replenishment.purchase_orders
            WHERE workflow_status IN ('DISPATCHED', 'COMPLETED')
              AND approved_at >= NOW() - (:days || ' days')::INTERVAL
            GROUP BY supplier_id
            """;

    private static final String WEEKLY_HISTORY_SQL = """
            SELECT DATE_TRUNC('week', approved_at)::DATE AS week_start,
                   COUNT(*)::INT AS po_count,
                   AVG(EXTRACT(EPOCH FROM (updated_at - approved_at)) / 86400.0) AS average_days
            FROM replenishment.purchase_orders
            WHERE workflow_status IN ('DISPATCHED', 'COMPLETED')
              AND approved_at >= NOW() - (:days || ' days')::INTERVAL
            GROUP BY DATE_TRUNC('week', approved_at)::DATE
            ORDER BY week_start DESC
            """;

    private static final String COUNT_PENDING_SQL = """
            SELECT COUNT(*) FROM replenishment.purchase_orders
            WHERE workflow_status = 'PENDING_APPROVAL'
            """;

    private static final String PO_METRICS_SQL = """
            SELECT supplier_id::UUID,
                   COUNT(*)::INT    AS total_po_count,
                   SUM(total_value) AS total_po_value
            FROM replenishment.purchase_orders
            WHERE created_at >= NOW() - (:days || ' days')::INTERVAL
            GROUP BY supplier_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ReplenishmentReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BigDecimal> averageCycleTimeDays(int days) {
        BigDecimal result = jdbc.queryForObject(
                AVG_CYCLE_TIME_SQL,
                new MapSqlParameterSource("days", days),
                BigDecimal.class
        );
        return Optional.ofNullable(result);
    }

    @Override
    public Map<UUID, int[]> fillRateBySupplier(int days) {
        Map<UUID, int[]> result = new HashMap<>();
        jdbc.query(FILL_RATE_SQL, new MapSqlParameterSource("days", days), rs -> {
            result.put(
                    rs.getObject("supplier_id", UUID.class),
                    new int[]{rs.getInt("completed"), rs.getInt("total")}
            );
        });
        return result;
    }

    @Override
    public List<CycleTimeDataPoint> findWeeklyCycleTimeHistory(int days) {
        return jdbc.query(
                WEEKLY_HISTORY_SQL,
                new MapSqlParameterSource("days", days),
                (rs, rowNum) -> new CycleTimeDataPoint(
                        rs.getObject("week_start", LocalDate.class),
                        rs.getBigDecimal("average_days"),
                        rs.getInt("po_count")
                )
        );
    }

    @Override
    public int countPendingApprovals() {
        Integer count = jdbc.queryForObject(COUNT_PENDING_SQL, new MapSqlParameterSource(), Integer.class);
        return count != null ? count : 0;
    }

    @Override
    public List<PoMetricsRow> findPoMetricsBySupplierId(int days) {
        return jdbc.query(
                PO_METRICS_SQL,
                new MapSqlParameterSource("days", days),
                (rs, rowNum) -> new PoMetricsRow(
                        rs.getObject("supplier_id", UUID.class),
                        rs.getInt("total_po_count"),
                        rs.getBigDecimal("total_po_value")
                )
        );
    }
}
