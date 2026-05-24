package com.smartretail.dfs.port.outbound;

import com.smartretail.dfs.domain.model.ForecastRow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port: write operations on the forecasting schema.
 * Implemented by ForecastWriteRepository (JDBC adapter).
 * No AWS imports — port layer is AWS-free.
 */
public interface ForecastPersistencePort {

    boolean forecastRunExists(UUID runId);

    /**
     * Batch-inserts demand_forecast rows using ON CONFLICT DO NOTHING so Lambda retries are safe.
     *
     * @return the number of rows actually inserted
     */
    int batchInsertForecastRows(UUID runId, List<ForecastRow> rows);

    void markRunCompleted(UUID runId, Instant completedAt);
}
