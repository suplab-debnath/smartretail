package com.smartretail.lambda.batchpostprocessor;

import java.time.LocalDate;

/**
 * One parsed forecast row from the SageMaker transform output CSV.
 * Serialised to JSON and posted inside IngestForecastResultsRequest to DFS.
 * Immutable record — validated in compact constructor.
 */
public record ForecastRowPayload(
        String skuId,
        String dcId,
        LocalDate forecastDate,
        int horizonDays,
        int p10,
        int p50,
        int p90) {

    public ForecastRowPayload {
        if (skuId == null || skuId.isBlank())  throw new IllegalArgumentException("skuId blank");
        if (dcId == null  || dcId.isBlank())   throw new IllegalArgumentException("dcId blank");
        if (forecastDate == null)              throw new IllegalArgumentException("forecastDate null");
        if (horizonDays <= 0)                  throw new IllegalArgumentException("horizonDays <= 0");
        if (p10 < 0 || p50 < 0 || p90 < 0)   throw new IllegalArgumentException("p-values < 0");
    }
}
