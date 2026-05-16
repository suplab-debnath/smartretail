package com.smartretail.ars.adapter.outbound.persistence;

import com.smartretail.ars.port.outbound.SupplierReadPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

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
}
