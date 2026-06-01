package com.smartretail.dfs.adapter.inbound.rest;

import com.smartretail.dfs.adapter.in.web.generated.model.ForecastDataResponse;
import com.smartretail.dfs.domain.model.ForecastData;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastResponseMapperTest {

    private final ForecastResponseMapper mapper = Mappers.getMapper(ForecastResponseMapper.class);

    @Test
    void shouldMapBandWithNonNullActualUnits() {
        var band = new ForecastData.Band(LocalDate.of(2026, 6, 1), 80, 100, 120, 95);
        ForecastData data = new ForecastData(
                "SKU-BEV-001", "DC-LONDON", 30,
                BigDecimal.valueOf(10.5), List.of(band), Instant.now());

        ForecastDataResponse response = mapper.toResponse(data);

        assertThat(response.getBands()).hasSize(1);
        assertThat(response.getBands().get(0).getActualUnits()).isEqualTo(95);
    }

    @Test
    void shouldMapBandWithNullActualUnits() {
        var band = new ForecastData.Band(LocalDate.of(2026, 6, 1), 80, 100, 120, null);
        ForecastData data = new ForecastData(
                "SKU-BEV-001", "DC-LONDON", 30,
                BigDecimal.valueOf(10.5), List.of(band), Instant.now());

        ForecastDataResponse response = mapper.toResponse(data);

        assertThat(response.getBands()).hasSize(1);
        assertThat(response.getBands().get(0).getActualUnits()).isNull();
    }

    @Test
    void shouldMapMultipleBands() {
        var b1 = new ForecastData.Band(LocalDate.of(2026, 6, 1), 80, 100, 120, 90);
        var b2 = new ForecastData.Band(LocalDate.of(2026, 6, 2), 85, 105, 125, null);
        ForecastData data = new ForecastData(
                "SKU-SNK-001", "DC-MANCHESTER", 30,
                BigDecimal.valueOf(8.0), List.of(b1, b2), Instant.now());

        ForecastDataResponse response = mapper.toResponse(data);

        assertThat(response.getSkuId()).isEqualTo("SKU-SNK-001");
        assertThat(response.getDcId()).isEqualTo("DC-MANCHESTER");
        assertThat(response.getBands()).hasSize(2);
        assertThat(response.getBands().get(1).getActualUnits()).isNull();
    }
}
