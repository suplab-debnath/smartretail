package com.smartretail.dfs.adapter.outbound.persistence;

import com.smartretail.dfs.domain.model.ForecastRow;
import com.smartretail.dfs.port.outbound.ForecastPersistencePort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JDBC adapter for write operations on the forecasting schema.
 * Uses ON CONFLICT DO NOTHING so Lambda retries are idempotent.
 * All SQL within forecasting schema — no cross-schema writes.
 */
@Repository
public class ForecastWriteRepository implements ForecastPersistencePort {

    private static final String RUN_EXISTS_SQL = """
            SELECT COUNT(1) FROM forecasting.forecast_runs WHERE run_id = :runId
            """;

    private static final String INSERT_ROW_SQL = """
            INSERT INTO forecasting.demand_forecasts
                (run_id, sku_id, dc_id, forecast_date, horizon_days, p10, p50, p90)
            VALUES
                (:runId, :skuId, :dcId, :forecastDate, :horizonDays, :p10, :p50, :p90)
            ON CONFLICT (run_id, sku_id, dc_id, forecast_date, horizon_days)
            DO NOTHING
            """;

    private static final String MARK_COMPLETED_SQL = """
            UPDATE forecasting.forecast_runs
            SET status       = 'COMPLETED',
                completed_at = :completedAt
            WHERE run_id = :runId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ForecastWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean forecastRunExists(UUID runId) {
        Integer count = jdbc.queryForObject(
                RUN_EXISTS_SQL,
                Map.of("runId", runId),
                Integer.class);
        return count != null && count > 0;
    }

    @Override
    public int batchInsertForecastRows(UUID runId, List<ForecastRow> rows) {
        MapSqlParameterSource[] params = rows.stream()
                .map(row -> new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("skuId", row.skuId())
                        .addValue("dcId", row.dcId())
                        .addValue("forecastDate", row.forecastDate())
                        .addValue("horizonDays", row.horizonDays())
                        .addValue("p10", row.p10())
                        .addValue("p50", row.p50())
                        .addValue("p90", row.p90()))
                .toArray(MapSqlParameterSource[]::new);

        int[] counts = jdbc.batchUpdate(INSERT_ROW_SQL, params);
        int inserted = 0;
        for (int c : counts) inserted += c;
        return inserted;
    }

    @Override
    public void markRunCompleted(UUID runId, Instant completedAt) {
        jdbc.update(MARK_COMPLETED_SQL,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("completedAt", Timestamp.from(completedAt)));
    }
}
