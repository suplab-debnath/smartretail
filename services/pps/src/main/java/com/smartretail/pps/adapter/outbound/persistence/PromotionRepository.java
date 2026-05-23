package com.smartretail.pps.adapter.outbound.persistence;

import com.smartretail.pps.port.outbound.PromotionReadPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Reads from promotions schema only — no cross-schema JOINs.
 */
@Repository
public class PromotionRepository implements PromotionReadPort {

    private static final String SCHEDULES_SQL = """
            SELECT promotion_id,
                   promotion_name,
                   sku_ids,
                   dc_ids,
                   discount_pct,
                   uplift_factor,
                   elasticity_coeff,
                   valid_from,
                   valid_to,
                   status,
                   source_event_id
            FROM promotions.promotion_schedules
            WHERE (CAST(:status AS TEXT) IS NULL OR status = :status)
            ORDER BY valid_from ASC
            """;

    private static final String DATA_FRESHNESS_SQL = """
            SELECT MAX(created_at) AS max_created
            FROM promotions.promotion_schedules
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PromotionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PromotionRow> findPromotionSchedules(String status) {
        return jdbc.query(
                SCHEDULES_SQL,
                new MapSqlParameterSource("status", status),
                (rs, rowNum) -> new PromotionRow(
                        toUuid(rs, "promotion_id"),
                        rs.getString("promotion_name"),
                        toStringList(rs.getArray("sku_ids")),
                        toStringList(rs.getArray("dc_ids")),
                        rs.getDouble("discount_pct"),
                        rs.getDouble("uplift_factor"),
                        toNullableDouble(rs, "elasticity_coeff"),
                        rs.getObject("valid_from", java.time.LocalDate.class),
                        rs.getObject("valid_to", java.time.LocalDate.class),
                        rs.getString("status"),
                        toUuid(rs, "source_event_id")
                )
        );
    }

    @Override
    public Instant findDataFreshness() {
        List<Instant> results = jdbc.query(
                DATA_FRESHNESS_SQL,
                new MapSqlParameterSource(),
                (rs, rowNum) -> toInstant(rs, "max_created")
        );
        return (results.isEmpty() || results.getFirst() == null) ? Instant.now() : results.getFirst();
    }

    private List<String> toStringList(java.sql.Array arr) throws java.sql.SQLException {
        if (arr == null) return List.of();
        String[] values = (String[]) arr.getArray();
        return Arrays.asList(values);
    }

    private Double toNullableDouble(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        double val = rs.getDouble(col);
        return rs.wasNull() ? null : val;
    }

    private Instant toInstant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : null;
    }

    private UUID toUuid(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        String val = rs.getString(col);
        return val != null ? UUID.fromString(val) : null;
    }
}
