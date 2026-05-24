package com.smartretail.dfs.domain.usecase;

import com.smartretail.dfs.domain.model.ForecastIngestionResult;
import com.smartretail.dfs.domain.model.ForecastRow;
import com.smartretail.dfs.domain.model.exception.ForecastRunNotFoundException;
import com.smartretail.dfs.port.outbound.ForecastPersistencePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ForecastIngestionUseCaseTest {

    @Mock
    private ForecastPersistencePort persistencePort;

    private ForecastIngestionUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ForecastIngestionUseCase(persistencePort);
    }

    @Test
    void shouldIngestRowsAndMarkRunCompleted() {
        UUID runId = UUID.randomUUID();
        List<ForecastRow> rows = List.of(
                new ForecastRow("SKU-001", "DC-LONDON", LocalDate.of(2026, 6, 1), 30, 80, 100, 130)
        );

        when(persistencePort.forecastRunExists(runId)).thenReturn(true);
        when(persistencePort.batchInsertForecastRows(eq(runId), eq(rows))).thenReturn(1);

        ForecastIngestionResult result = useCase.ingest(runId, rows);

        assertThat(result.runId()).isEqualTo(runId);
        assertThat(result.rowsInserted()).isEqualTo(1);
        assertThat(result.ingestedAt()).isNotNull();

        verify(persistencePort).markRunCompleted(eq(runId), any());
    }

    @Test
    void shouldThrowWhenRunDoesNotExist() {
        UUID runId = UUID.randomUUID();
        when(persistencePort.forecastRunExists(runId)).thenReturn(false);

        assertThatThrownBy(() -> useCase.ingest(runId, List.of()))
                .isInstanceOf(ForecastRunNotFoundException.class)
                .hasMessageContaining(runId.toString())
                .satisfies(e -> assertThat(((ForecastRunNotFoundException) e).getRunId()).isEqualTo(runId));

        verify(persistencePort, never()).batchInsertForecastRows(any(), any());
        verify(persistencePort, never()).markRunCompleted(any(), any());
    }

    @Test
    void shouldReturnCorrectRowCount() {
        UUID runId = UUID.randomUUID();
        List<ForecastRow> rows = List.of(
                new ForecastRow("SKU-001", "DC-LONDON", LocalDate.of(2026, 6, 1), 30, 80, 100, 130),
                new ForecastRow("SKU-002", "DC-LONDON", LocalDate.of(2026, 6, 1), 30, 50, 70, 95)
        );

        when(persistencePort.forecastRunExists(runId)).thenReturn(true);
        when(persistencePort.batchInsertForecastRows(eq(runId), eq(rows))).thenReturn(2);

        ForecastIngestionResult result = useCase.ingest(runId, rows);

        assertThat(result.rowsInserted()).isEqualTo(2);
    }
}
