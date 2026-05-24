package com.smartretail.dfs.port.inbound;

import com.smartretail.dfs.domain.model.ForecastIngestionResult;
import com.smartretail.dfs.domain.model.ForecastRow;

import java.util.List;
import java.util.UUID;

/**
 * Inbound port: persist a batch of forecast rows for a completed forecast run.
 * Called by ForecastController (REST inbound adapter).
 * No AWS imports — port layer is AWS-free.
 */
public interface ForecastWritePort {

    /**
     * Persists all rows for the given run, then transitions the run status to COMPLETED.
     *
     * @throws com.smartretail.dfs.domain.model.exception.ForecastRunNotFoundException
     *         if runId does not exist in forecasting.forecast_runs
     */
    ForecastIngestionResult ingest(UUID runId, List<ForecastRow> rows);
}
