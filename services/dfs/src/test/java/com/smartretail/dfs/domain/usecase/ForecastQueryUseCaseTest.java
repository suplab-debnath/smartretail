package com.smartretail.dfs.domain.usecase;

import com.smartretail.dfs.domain.model.ForecastData;
import com.smartretail.dfs.port.outbound.ForecastReadPort;
import com.smartretail.dfs.port.outbound.ForecastReadPort.ForecastBandRow;
import com.smartretail.dfs.port.outbound.ForecastReadPort.LatestRunInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForecastQueryUseCaseTest {

    @Mock private ForecastReadPort forecastReadPort;

    private ForecastQueryUseCase useCase;

    private static final String SKU_ID = "SKU-BEV-001";
    private static final String DC_ID  = "DC-LONDON";

    @BeforeEach
    void setUp() {
        useCase = new ForecastQueryUseCase(forecastReadPort);
    }

    @Test
    void getForecast_returnsBandsWithMetadata() {
        LatestRunInfo runInfo = new LatestRunInfo(BigDecimal.valueOf(0.08), 30, Instant.now());
        List<ForecastBandRow> rows = List.of(
                new ForecastBandRow(LocalDate.now(),       100, 120, 140, null),
                new ForecastBandRow(LocalDate.now().plusDays(1), 90, 110, 130, 105)
        );
        when(forecastReadPort.findLatestRunInfo(SKU_ID, DC_ID, 30)).thenReturn(runInfo);
        when(forecastReadPort.findForecastBands(SKU_ID, DC_ID, 30)).thenReturn(rows);

        ForecastData result = useCase.getForecast(SKU_ID, DC_ID, 30);

        assertThat(result.skuId()).isEqualTo(SKU_ID);
        assertThat(result.dcId()).isEqualTo(DC_ID);
        assertThat(result.horizonDays()).isEqualTo(30);
        assertThat(result.latestMape()).isEqualByComparingTo(BigDecimal.valueOf(0.08));
        assertThat(result.bands()).hasSize(2);
        assertThat(result.dataFreshness()).isEqualTo(runInfo.completedAt());
    }

    @Test
    void getForecast_bandsMappedCorrectly() {
        LocalDate date = LocalDate.of(2026, 5, 20);
        LatestRunInfo runInfo = new LatestRunInfo(BigDecimal.valueOf(0.10), 14, Instant.now());
        when(forecastReadPort.findLatestRunInfo(SKU_ID, DC_ID, 14)).thenReturn(runInfo);
        when(forecastReadPort.findForecastBands(SKU_ID, DC_ID, 14)).thenReturn(
                List.of(new ForecastBandRow(date, 50, 75, 100, 80)));

        ForecastData result = useCase.getForecast(SKU_ID, DC_ID, 14);

        ForecastData.Band band = result.bands().get(0);
        assertThat(band.forecastDate()).isEqualTo(date);
        assertThat(band.p10()).isEqualTo(50);
        assertThat(band.p50()).isEqualTo(75);
        assertThat(band.p90()).isEqualTo(100);
        assertThat(band.actualUnits()).isEqualTo(80);
    }

    @Test
    void getForecast_emptyBands_returnsEmptyList() {
        LatestRunInfo runInfo = new LatestRunInfo(BigDecimal.ZERO, 30, Instant.now());
        when(forecastReadPort.findLatestRunInfo(SKU_ID, DC_ID, 30)).thenReturn(runInfo);
        when(forecastReadPort.findForecastBands(SKU_ID, DC_ID, 30)).thenReturn(List.of());

        ForecastData result = useCase.getForecast(SKU_ID, DC_ID, 30);

        assertThat(result.bands()).isEmpty();
    }

    @Test
    void getForecast_actualUnitsNullPreserved() {
        LatestRunInfo runInfo = new LatestRunInfo(BigDecimal.valueOf(0.12), 7, Instant.now());
        when(forecastReadPort.findLatestRunInfo(SKU_ID, DC_ID, 7)).thenReturn(runInfo);
        when(forecastReadPort.findForecastBands(SKU_ID, DC_ID, 7)).thenReturn(
                List.of(new ForecastBandRow(LocalDate.now(), 10, 20, 30, null)));

        ForecastData result = useCase.getForecast(SKU_ID, DC_ID, 7);

        assertThat(result.bands().get(0).actualUnits()).isNull();
    }
}
