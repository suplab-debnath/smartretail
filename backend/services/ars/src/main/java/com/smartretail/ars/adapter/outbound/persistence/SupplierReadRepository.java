package com.smartretail.ars.adapter.outbound.persistence;

import com.smartretail.ars.port.outbound.SupplierReadPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
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

    private static final String SUPPLIER_ORDERS_SQL = """
            SELECT
                sp.supplier_po_id,
                CAST(sp.po_id AS UUID)    AS po_id,
                sp.supplier_id,
                sr.supplier_name,
                sp.sku_id,
                sp.dc_id,
                COALESCE(sp.quantity, 0)  AS quantity,
                sp.po_status              AS shipment_status,
                sp.confirmed_at,
                sp.dispatched_at,
                sp.eta,
                MAX(su.created_at)        AS last_update_at
            FROM supplier.supplier_pos sp
            JOIN supplier.supplier_records sr
                ON sp.supplier_id = sr.supplier_id
            LEFT JOIN supplier.shipment_updates su
                ON sp.supplier_po_id = su.supplier_po_id
            WHERE (:status IS NULL OR sp.po_status = :status)
            GROUP BY sp.supplier_po_id, sp.po_id, sp.supplier_id, sr.supplier_name,
                     sp.sku_id, sp.dc_id, sp.quantity, sp.po_status,
                     sp.confirmed_at, sp.dispatched_at, sp.eta
            ORDER BY
                CASE WHEN sp.po_status = 'EXCEPTION' THEN 0 ELSE 1 END,
                sp.eta ASC NULLS LAST
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
    public List<SupplierOrderRow> findSupplierOrders(String status) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", status, Types.VARCHAR);
        return jdbc.query(SUPPLIER_ORDERS_SQL, params, (rs, rowNum) -> new SupplierOrderRow(
                rs.getObject("supplier_po_id", UUID.class),
                rs.getObject("po_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"),
                rs.getString("sku_id"),
                rs.getString("dc_id"),
                rs.getInt("quantity"),
                rs.getString("shipment_status"),
                rs.getTimestamp("confirmed_at") != null
                        ? rs.getTimestamp("confirmed_at").toInstant() : null,
                rs.getTimestamp("dispatched_at") != null
                        ? rs.getTimestamp("dispatched_at").toInstant() : null,
                rs.getObject("eta", LocalDate.class),
                rs.getTimestamp("last_update_at") != null
                        ? rs.getTimestamp("last_update_at").toInstant() : null
        ));
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
