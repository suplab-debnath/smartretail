package com.smartretail.ars.adapter.outbound.persistence;

import com.smartretail.ars.domain.model.ExecutiveDashboard.MapeDataPoint;
import com.smartretail.ars.port.outbound.ForecastReadPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
}
