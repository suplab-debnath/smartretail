package com.smartretail.ars.adapter.outbound.persistence;

import com.smartretail.ars.domain.model.ExecutiveDashboard.MapeDataPoint;
import com.smartretail.ars.port.outbound.ForecastReadPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Repository
public class ForecastReadRepository implements ForecastReadPort {

    private static final String MAPE_HISTORY_SQL = """
            SELECT DATE(started_at) AS run_date, mape
            FROM forecasting.forecast_runs
            WHERE status = 'COMPLETED'
              AND mape IS NOT NULL
            ORDER BY started_at DESC
            LIMIT :limit
            """;

    private static final String LATEST_MAPE_SQL = """
            SELECT mape, completed_at
            FROM forecasting.forecast_runs
            WHERE status = 'COMPLETED'
              AND mape IS NOT NULL
            ORDER BY started_at DESC
            LIMIT 1
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ForecastReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MapeDataPoint> findRecentMapeHistory(int limit) {
        return jdbc.query(
                MAPE_HISTORY_SQL,
                new MapSqlParameterSource("limit", limit),
                (rs, rowNum) -> new MapeDataPoint(
                        rs.getObject("run_date", LocalDate.class),
                        rs.getBigDecimal("mape")
                )
        );
    }

    @Override
    public LatestMape findLatestMape() {
        List<LatestMape> results = jdbc.query(
                LATEST_MAPE_SQL,
                new MapSqlParameterSource(),
                (rs, rowNum) -> new LatestMape(
                        rs.getBigDecimal("mape"),
                        rs.getObject("completed_at", java.sql.Timestamp.class) != null
                                ? rs.getObject("completed_at", java.sql.Timestamp.class).toInstant()
                                : Instant.now()
                )
        );
        return results.isEmpty()
                ? new LatestMape(BigDecimal.ZERO, Instant.now())
                : results.getFirst();
    }

    @Override
    public int countSkusWithForecastByDc(String dcId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT sku_id)
                FROM forecasting.demand_forecasts
                WHERE dc_id = :dcId
                  AND forecast_date BETWEEN CURRENT_DATE AND CURRENT_DATE + 7
                """,
                new MapSqlParameterSource("dcId", dcId),
                Integer.class
        );
        return count != null ? count : 0;
    }
}
