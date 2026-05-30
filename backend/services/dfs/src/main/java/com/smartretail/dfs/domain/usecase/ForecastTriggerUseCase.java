package com.smartretail.dfs.domain.usecase;

import com.smartretail.dfs.port.inbound.ForecastTriggerPort;
import com.smartretail.dfs.port.outbound.ForecastPersistencePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Registers a new forecast run in the forecasting schema.
 * Zero AWS imports — domain use case is cloud-provider agnostic.
 */
@Service
public class ForecastTriggerUseCase implements ForecastTriggerPort {

    private static final Logger log = LoggerFactory.getLogger(ForecastTriggerUseCase.class);

    private final ForecastPersistencePort forecastPersistencePort;

    public ForecastTriggerUseCase(ForecastPersistencePort forecastPersistencePort) {
        this.forecastPersistencePort = forecastPersistencePort;
    }

    @Override
    @Transactional
    public UUID registerRun(String triggeredBy) {
        UUID runId = forecastPersistencePort.registerRun(triggeredBy);
        log.info("Forecast run registered: runId={} triggeredBy={}", runId, triggeredBy);
        return runId;
    }
}
