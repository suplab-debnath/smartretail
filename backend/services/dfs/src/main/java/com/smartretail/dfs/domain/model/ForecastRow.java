package com.smartretail.dfs.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A single forecast row to be persisted into forecasting.demand_forecasts.
 * No AWS imports — pure domain model.
 */
public record ForecastRow(
        String skuId,
        String dcId,
        LocalDate forecastDate,
        int horizonDays,
        int p10,
        int p50,
        int p90) {

    public ForecastRow {
        Objects.requireNonNull(skuId, "skuId must not be null");
        Objects.requireNonNull(dcId, "dcId must not be null");
        Objects.requireNonNull(forecastDate, "forecastDate must not be null");
        if (skuId.isBlank())   throw new IllegalArgumentException("skuId must not be blank");
        if (dcId.isBlank())    throw new IllegalArgumentException("dcId must not be blank");
        if (horizonDays <= 0)  throw new IllegalArgumentException("horizonDays must be > 0");
        if (p10 < 0)           throw new IllegalArgumentException("p10 must be >= 0");
        if (p50 < 0)           throw new IllegalArgumentException("p50 must be >= 0");
        if (p90 < 0)           throw new IllegalArgumentException("p90 must be >= 0");
    }
}
