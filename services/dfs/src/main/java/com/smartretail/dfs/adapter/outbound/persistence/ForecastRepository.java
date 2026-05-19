package com.smartretail.dfs.adapter.outbound.persistence;

import com.smartretail.dfs.port.outbound.ForecastReadPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Reads from forecasting schema only — no cross-schema JOINs.
 */
@Repository
public class ForecastRepository implements ForecastReadPort {

    /**
     * Returns demand forecast bands from the latest COMPLETED run for a SKU × DC × horizon.
     * actual_units is populated for past dates from the sales schema via the ETL pipeline (already in forecasting.demand_forecasts).
     */
    private static final String FORECAST_BANDS_SQL = """
            SELECT df.forecast_date,
                   df.p10,
                   df.p50,
                   df.p90,
                   df.actual_units
            FROM forecasting.demand_forecasts df
            WHERE df.sku_id       = :skuId
              AND df.dc_id        = :dcId
              AND df.horizon_days = :horizonDays
              AND df.run_id = (
                  SELECT run_id
                  FROM forecasting.forecast_runs
                  WHERE status = 'COMPLETED'
                    AND mape IS NOT NULL
                  ORDER BY started_at DESC
                  LIMIT 1
              )
            ORDER BY df.forecast_date ASC
            """;

    private static final String LATEST_RUN_SQL = """
            SELECT fr.mape,
                   df.horizon_days,
                   fr.completed_at
            FROM forecasting.forecast_runs fr
            JOIN forecasting.demand_forecasts df ON df.run_id = fr.run_id
            WHERE fr.status    = 'COMPLETED'
              AND fr.mape IS NOT NULL
              AND df.sku_id    = :skuId
              AND df.dc_id     = :dcId
              AND df.horizon_days = :horizonDays
            ORDER BY fr.started_at DESC
            LIMIT 1
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ForecastRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ForecastBandRow> findForecastBands(String skuId, String dcId, int horizonDays) {
        return jdbc.query(
                FORECAST_BANDS_SQL,
                new MapSqlParameterSource()
                        .addValue("skuId", skuId)
                        .addValue("dcId", dcId)
                        .addValue("horizonDays", horizonDays),
                (rs, rowNum) -> new ForecastBandRow(
                        rs.getObject("forecast_date", LocalDate.class),
                        rs.getInt("p10"),
                        rs.getInt("p50"),
                        rs.getInt("p90"),
                        rs.getObject("actual_units") != null ? rs.getInt("actual_units") : null
                )
        );
    }

    @Override
    public LatestRunInfo findLatestRunInfo(String skuId, String dcId, int horizonDays) {
        List<LatestRunInfo> results = jdbc.query(
                LATEST_RUN_SQL,
                new MapSqlParameterSource()
                        .addValue("skuId", skuId)
                        .addValue("dcId", dcId)
                        .addValue("horizonDays", horizonDays),
                (rs, rowNum) -> new LatestRunInfo(
                        rs.getBigDecimal("mape"),
                        rs.getInt("horizon_days"),
                        toInstant(rs, "completed_at")
                )
        );
        return results.isEmpty()
                ? new LatestRunInfo(BigDecimal.ZERO, horizonDays, Instant.now())
                : results.get(0);
    }

    private Instant toInstant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : Instant.now();
    }
}
