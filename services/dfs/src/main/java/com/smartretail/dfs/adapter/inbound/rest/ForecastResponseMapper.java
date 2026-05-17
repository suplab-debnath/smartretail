package com.smartretail.dfs.adapter.inbound.rest;

import com.smartretail.dfs.adapter.in.web.generated.model.ForecastBand;
import com.smartretail.dfs.adapter.in.web.generated.model.ForecastDataResponse;
import com.smartretail.dfs.adapter.in.web.generated.model.ForecastDataResponse.HorizonDaysEnum;
import com.smartretail.dfs.domain.model.ForecastData;
import org.mapstruct.Mapper;

import java.time.ZoneOffset;
import java.util.List;

@Mapper(componentModel = "spring")
public interface ForecastResponseMapper {

    default ForecastDataResponse toResponse(ForecastData data) {
        List<ForecastBand> bands = data.bands().stream()
                .map(b -> {
                    ForecastBand band = new ForecastBand(b.forecastDate(), b.p10(), b.p50(), b.p90());
                    band.setActualUnits(b.actualUnits());
                    return band;
                })
                .toList();

        return new ForecastDataResponse(
                data.skuId(),
                data.dcId(),
                HorizonDaysEnum.fromValue(data.horizonDays()),
                data.latestMape().doubleValue(),
                bands,
                data.dataFreshness().atOffset(ZoneOffset.UTC)
        );
    }
}
