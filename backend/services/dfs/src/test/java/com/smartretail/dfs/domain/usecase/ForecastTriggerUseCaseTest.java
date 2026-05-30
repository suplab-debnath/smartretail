package com.smartretail.dfs.domain.usecase;

import com.smartretail.dfs.port.outbound.ForecastPersistencePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ForecastTriggerUseCaseTest {

    @Mock
    private ForecastPersistencePort persistencePort;

    private ForecastTriggerUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ForecastTriggerUseCase(persistencePort);
    }

    @Test
    void registerRun_returnsRunIdFromPort() {
        UUID runId = UUID.randomUUID();
        when(persistencePort.registerRun("SCHEDULED")).thenReturn(runId);

        UUID result = useCase.registerRun("SCHEDULED");

        assertThat(result).isEqualTo(runId);
        verify(persistencePort).registerRun("SCHEDULED");
    }

    @Test
    void registerRun_delegatesTriggeredByToPort() {
        UUID runId = UUID.randomUUID();
        when(persistencePort.registerRun("MANUAL")).thenReturn(runId);

        UUID result = useCase.registerRun("MANUAL");

        assertThat(result).isEqualTo(runId);
        verify(persistencePort).registerRun("MANUAL");
    }
}
