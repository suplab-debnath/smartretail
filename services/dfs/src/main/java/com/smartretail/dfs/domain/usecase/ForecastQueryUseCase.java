package com.smartretail.dfs.domain.usecase;

import com.smartretail.dfs.domain.model.ForecastData;
import com.smartretail.dfs.port.inbound.ForecastQueryPort;
import com.smartretail.dfs.port.outbound.ForecastReadPort;
import com.smartretail.dfs.port.outbound.ForecastReadPort.ForecastBandRow;
import com.smartretail.dfs.port.outbound.ForecastReadPort.LatestRunInfo;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Domain use case: fetch forecast bands + run metadata for a SKU × DC × horizon.
 * Single-schema reads only (forecasting schema via ForecastReadPort).
 */
@Service
public class ForecastQueryUseCase implements ForecastQueryPort {

    private final ForecastReadPort forecastReadPort;

    public ForecastQueryUseCase(ForecastReadPort forecastReadPort) {
        this.forecastReadPort = forecastReadPort;
    }

    @Override
    public ForecastData getForecast(String skuId, String dcId, int horizonDays) {
        LatestRunInfo runInfo = forecastReadPort.findLatestRunInfo(skuId, dcId, horizonDays);
        List<ForecastBandRow> rows = forecastReadPort.findForecastBands(skuId, dcId, horizonDays);

        List<ForecastData.Band> bands = rows.stream()
                .map(r -> new ForecastData.Band(
                        r.forecastDate(), r.p10(), r.p50(), r.p90(), r.actualUnits()))
                .toList();

        return new ForecastData(
                skuId,
                dcId,
                runInfo.horizonDays(),
                runInfo.mape(),
                bands,
                runInfo.completedAt());
    }
}
