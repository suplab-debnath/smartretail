package com.smartretail.dfs.adapter.outbound.persistence;

import com.smartretail.dfs.domain.model.ForecastRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ForecastWriteRepositoryTest {

    @Mock
    private NamedParameterJdbcOperations jdbc;

    private ForecastWriteRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ForecastWriteRepository(jdbc);
    }

    @Test
    void registerRun_delegatesToJdbcAndReturnsRunId() {
        UUID expected = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), anyMap(), eq(UUID.class))).thenReturn(expected);

        UUID result = repository.registerRun("SCHEDULED");

        assertThat(result).isEqualTo(expected);
        verify(jdbc).queryForObject(contains("INSERT INTO forecasting.forecast_runs"),
                argThat((Map<String, ?> m) -> "SCHEDULED".equals(m.get("triggeredBy"))),
                eq(UUID.class));
    }

    @Test
    void forecastRunExists_runPresent_returnsTrue() {
        UUID runId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(1);

        boolean exists = repository.forecastRunExists(runId);

        assertThat(exists).isTrue();
    }

    @Test
    void forecastRunExists_runAbsent_returnsFalse() {
        UUID runId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);

        boolean exists = repository.forecastRunExists(runId);

        assertThat(exists).isFalse();
    }

    @Test
    void forecastRunExists_nullCount_returnsFalse() {
        UUID runId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(null);

        boolean exists = repository.forecastRunExists(runId);

        assertThat(exists).isFalse();
    }

    @Test
    void batchInsertForecastRows_callsBatchUpdateWithCorrectParams() {
        UUID runId = UUID.randomUUID();
        List<ForecastRow> rows = List.of(
                new ForecastRow("SKU-BEV-001", "DC-LONDON", LocalDate.of(2026, 6, 1), 30, 80, 100, 120),
                new ForecastRow("SKU-BEV-001", "DC-LONDON", LocalDate.of(2026, 6, 2), 30, 85, 105, 125));

        when(jdbc.batchUpdate(anyString(), any(MapSqlParameterSource[].class)))
                .thenReturn(new int[]{1, 1});

        int inserted = repository.batchInsertForecastRows(runId, rows);

        assertThat(inserted).isEqualTo(2);

        ArgumentCaptor<MapSqlParameterSource[]> captor =
                ArgumentCaptor.forClass(MapSqlParameterSource[].class);
        verify(jdbc).batchUpdate(contains("INSERT INTO forecasting.demand_forecasts"), captor.capture());

        MapSqlParameterSource[] params = captor.getValue();
        assertThat(params).hasSize(2);
        assertThat(params[0].getValue("skuId")).isEqualTo("SKU-BEV-001");
        assertThat(params[0].getValue("dcId")).isEqualTo("DC-LONDON");
        assertThat(params[0].getValue("runId")).isEqualTo(runId);
        assertThat(params[0].getValue("horizonDays")).isEqualTo(30);
        assertThat(params[0].getValue("p50")).isEqualTo(100);
    }

    @Test
    void batchInsertForecastRows_onConflictDoNothing_countsOnlyInserted() {
        UUID runId = UUID.randomUUID();
        List<ForecastRow> rows = List.of(
                new ForecastRow("SKU-A", "DC-X", LocalDate.now(), 30, 10, 20, 30));

        // ON CONFLICT DO NOTHING returns 0 for the conflicting row
        when(jdbc.batchUpdate(anyString(), any(MapSqlParameterSource[].class)))
                .thenReturn(new int[]{0});

        int inserted = repository.batchInsertForecastRows(runId, rows);

        assertThat(inserted).isEqualTo(0);
    }

    @Test
    void markRunCompleted_callsUpdateWithCorrectRunId() {
        UUID runId = UUID.randomUUID();
        Instant completedAt = Instant.now();

        repository.markRunCompleted(runId, completedAt);

        ArgumentCaptor<MapSqlParameterSource> captor =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("UPDATE forecasting.forecast_runs"), captor.capture());

        MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("runId")).isEqualTo(runId);
        assertThat(params.getValue("completedAt")).isNotNull();
    }
}
