package com.smartretail.dfs.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Domain model for demand forecast probability bands.
 * Assembled from forecasting schema — no cross-schema data.
 */
public record ForecastData(
        String skuId,
        String dcId,
        int horizonDays,
        BigDecimal latestMape,
        List<Band> bands,
        Instant dataFreshness) {

    public record Band(
            LocalDate forecastDate,
            int p10,
            int p50,
            int p90,
            Integer actualUnits) {}
}
