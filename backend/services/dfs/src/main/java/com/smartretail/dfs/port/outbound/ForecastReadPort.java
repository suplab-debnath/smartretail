package com.smartretail.dfs.port.outbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface ForecastReadPort {

    /**
     * Returns P10/P50/P90 demand forecast bands for a SKU × DC × horizon from the
     * latest COMPLETED forecast run. Queries forecasting schema only.
     */
    List<ForecastBandRow> findForecastBands(String skuId, String dcId, int horizonDays);

    /**
     * Returns the MAPE and run timestamp of the latest COMPLETED forecast run.
     * Used to populate latestMape and dataFreshness on the response.
     */
    LatestRunInfo findLatestRunInfo(String skuId, String dcId, int horizonDays);

    record ForecastBandRow(
            LocalDate forecastDate,
            int p10,
            int p50,
            int p90,
            Integer actualUnits) {}

    record LatestRunInfo(
            BigDecimal mape,
            int horizonDays,
            Instant completedAt) {}
}
