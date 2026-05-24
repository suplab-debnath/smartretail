package com.smartretail.dfs.domain.usecase;

import com.smartretail.dfs.domain.model.ForecastIngestionResult;
import com.smartretail.dfs.domain.model.ForecastRow;
import com.smartretail.dfs.domain.model.exception.ForecastRunNotFoundException;
import com.smartretail.dfs.port.inbound.ForecastWritePort;
import com.smartretail.dfs.port.outbound.ForecastPersistencePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Validates the run exists, persists all rows atomically, then marks the run COMPLETED.
 * Zero AWS imports — application service lives in domain.usecase.
 */
@Service
public class ForecastIngestionUseCase implements ForecastWritePort {

    private static final Logger log = LoggerFactory.getLogger(ForecastIngestionUseCase.class);

    private final ForecastPersistencePort forecastPersistencePort;

    public ForecastIngestionUseCase(ForecastPersistencePort forecastPersistencePort) {
        this.forecastPersistencePort = forecastPersistencePort;
    }

    @Override
    @Transactional
    public ForecastIngestionResult ingest(UUID runId, List<ForecastRow> rows) {
        if (!forecastPersistencePort.forecastRunExists(runId)) {
            throw new ForecastRunNotFoundException(runId);
        }

        int rowsInserted = forecastPersistencePort.batchInsertForecastRows(runId, rows);
        Instant completedAt = Instant.now();
        forecastPersistencePort.markRunCompleted(runId, completedAt);

        log.info("Forecast run {} ingested: rowsInserted={}", runId, rowsInserted);
        return new ForecastIngestionResult(runId, rowsInserted, completedAt);
    }
}
