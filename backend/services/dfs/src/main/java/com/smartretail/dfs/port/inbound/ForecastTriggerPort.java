package com.smartretail.dfs.port.inbound;

import java.util.UUID;

/**
 * Inbound port: register a new forecast run triggered by the ML Trigger Lambda.
 * Called by ForecastController (REST inbound adapter).
 * No AWS imports — port layer is AWS-free.
 */
public interface ForecastTriggerPort {

    /**
     * Creates a forecast_runs row in TRIGGERED status and returns the generated runId.
     * The runId is passed to SageMaker as a pipeline parameter so transform output is
     * written to sagemaker/output/{runId}/part-*.csv.
     *
     * @param triggeredBy  "SCHEDULED" or "MANUAL"
     * @return the generated run UUID
     */
    UUID registerRun(String triggeredBy);
}
