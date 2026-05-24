package com.smartretail.dfs.port.inbound;

import com.smartretail.dfs.domain.model.ForecastData;

/**
 * Inbound port: assemble forecast bands for a SKU × DC × horizon.
 */
public interface ForecastQueryPort {
    ForecastData getForecast(String skuId, String dcId, int horizonDays);
}
