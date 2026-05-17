package com.smartretail.ars.adapter.outbound.persistence;

import com.smartretail.ars.port.outbound.SupplierReadPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class SupplierReadRepository implements SupplierReadPort {

    private static final String SUPPLIER_NAMES_SQL = """
            SELECT supplier_id, supplier_name
            FROM supplier.supplier_records
            WHERE status = 'ACTIVE'
            """;

    private static final String DELIVERY_STATS_SQL = """
            SELECT supplier_id,
                   COUNT(*) FILTER (WHERE DATE(dispatched_at) < eta - 2)::INT            AS early_count,
                   COUNT(*) FILTER (WHERE DATE(dispatched_at) BETWEEN eta - 2 AND eta)::INT AS on_time_count,
                   COUNT(*) FILTER (WHERE DATE(dispatched_at) > eta)::INT                 AS late_count
            FROM supplier.supplier_pos
            WHERE dispatched_at IS NOT NULL
            GROUP BY supplier_id
            """;

    /**
     * On-time = dispatched on or before the agreed ETA.
     * All joins within supplier schema — no cross-schema join.
     */
    private static final String SHIPMENT_METRICS_SQL = """
            SELECT sp.supplier_id,
                   COUNT(*) FILTER (
                       WHERE sp.dispatched_at IS NOT NULL
                         AND sp.eta IS NOT NULL
                         AND DATE(sp.dispatched_at) <= sp.eta
                   )::INT AS on_time_count,
                   COUNT(*) FILTER (WHERE sp.dispatched_at IS NOT NULL)::INT AS total_shipped
            FROM supplier.supplier_pos sp
            GROUP BY sp.supplier_id
            """;

    /**
     * Lead-time variance = dispatched_at vs eta (negative = early, positive = late).
     * No lead_time_days column in supplier_records — uses eta as the expected delivery date.
     */
    private static final String LEAD_TIME_VARIANCE_SQL = """
            SELECT sp.supplier_id,
                   AVG(
                       DATE(sp.dispatched_at) - sp.eta
                   ) AS avg_variance_days
            FROM supplier.supplier_pos sp
            WHERE sp.dispatched_at IS NOT NULL
              AND sp.eta IS NOT NULL
            GROUP BY sp.supplier_id
            """;

    private static final String OPEN_EXCEPTIONS_SQL = """
            SELECT supplier_id, COUNT(*)::INT AS exception_count
            FROM supplier.supplier_pos
            WHERE po_status = 'EXCEPTION'
            GROUP BY supplier_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SupplierReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, String> findActiveSupplierNames() {
        Map<UUID, String> result = new HashMap<>();
        jdbc.query(SUPPLIER_NAMES_SQL, new MapSqlParameterSource(), rs -> {
            result.put(rs.getObject("supplier_id", UUID.class), rs.getString("supplier_name"));
        });
        return result;
    }

    @Override
    public List<SupplierDeliveryStats> findDeliveryStats() {
        return jdbc.query(DELIVERY_STATS_SQL, new MapSqlParameterSource(),
                (rs, rowNum) -> new SupplierDeliveryStats(
                        rs.getObject("supplier_id", UUID.class),
                        rs.getInt("early_count"),
                        rs.getInt("on_time_count"),
                        rs.getInt("late_count")
                )
        );
    }

    @Override
    public List<ShipmentMetricsRow> findShipmentMetricsBySupplierId() {
        return jdbc.query(SHIPMENT_METRICS_SQL, new MapSqlParameterSource(),
                (rs, rowNum) -> new ShipmentMetricsRow(
                        rs.getObject("supplier_id", UUID.class),
                        rs.getInt("on_time_count"),
                        rs.getInt("total_shipped")
                )
        );
    }

    @Override
    public Map<UUID, BigDecimal> findAvgLeadTimeVarianceBySupplierId() {
        Map<UUID, BigDecimal> result = new HashMap<>();
        jdbc.query(LEAD_TIME_VARIANCE_SQL, new MapSqlParameterSource(), rs -> {
            result.put(
                    rs.getObject("supplier_id", UUID.class),
                    rs.getBigDecimal("avg_variance_days")
            );
        });
        return result;
    }

    @Override
    public Map<UUID, Integer> findOpenExceptionsBySupplierId() {
        Map<UUID, Integer> result = new HashMap<>();
        jdbc.query(OPEN_EXCEPTIONS_SQL, new MapSqlParameterSource(), rs -> {
            result.put(
                    rs.getObject("supplier_id", UUID.class),
                    rs.getInt("exception_count")
            );
        });
        return result;
    }

}
