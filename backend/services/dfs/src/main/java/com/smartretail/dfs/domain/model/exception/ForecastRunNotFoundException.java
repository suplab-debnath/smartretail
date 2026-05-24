package com.smartretail.dfs.domain.model.exception;

import java.util.UUID;

/**
 * Thrown when the requested forecast run does not exist in forecasting.forecast_runs.
 */
public class ForecastRunNotFoundException extends RuntimeException {

    private final UUID runId;

    public ForecastRunNotFoundException(UUID runId) {
        super("Forecast run not found: " + runId);
        this.runId = runId;
    }

    public UUID getRunId() { return runId; }
}
