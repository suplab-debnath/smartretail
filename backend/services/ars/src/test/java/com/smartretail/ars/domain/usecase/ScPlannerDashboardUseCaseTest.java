package com.smartretail.ars.domain.usecase;

import com.smartretail.ars.domain.model.ScPlannerDashboard;
import com.smartretail.ars.domain.model.ScPlannerDashboard.MapeStatus;
import com.smartretail.ars.port.outbound.ForecastReadPort;
import com.smartretail.ars.port.outbound.ForecastReadPort.LatestMape;
import com.smartretail.ars.port.outbound.InventoryReadPort;
import com.smartretail.ars.port.outbound.ReplenishmentReadPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScPlannerDashboardUseCaseTest {

    @Mock private ForecastReadPort forecastReadPort;
    @Mock private InventoryReadPort inventoryReadPort;
    @Mock private ReplenishmentReadPort replenishmentReadPort;

    private ScPlannerDashboardUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ScPlannerDashboardUseCase(forecastReadPort, inventoryReadPort, replenishmentReadPort);
    }

    @Test
    void assemble_mapeWithinThreshold_returnsWithinThresholdStatus() {
        when(replenishmentReadPort.countPendingApprovals()).thenReturn(3);
        when(inventoryReadPort.countActiveAlerts()).thenReturn(7);
        when(forecastReadPort.findLatestMape()).thenReturn(new LatestMape(BigDecimal.valueOf(0.10), Instant.now()));

        ScPlannerDashboard result = useCase.assemble();

        assertThat(result.pendingApprovalCount()).isEqualTo(3);
        assertThat(result.activeAlertCount()).isEqualTo(7);
        assertThat(result.forecastAccuracy().status()).isEqualTo(MapeStatus.WITHIN_THRESHOLD);
        assertThat(result.forecastAccuracy().latestMape()).isEqualByComparingTo(BigDecimal.valueOf(0.10));
        assertThat(result.dataFreshness()).isNotNull();
    }

    @Test
    void assemble_mapeAboveThreshold_returnsAboveThresholdStatus() {
        when(replenishmentReadPort.countPendingApprovals()).thenReturn(0);
        when(inventoryReadPort.countActiveAlerts()).thenReturn(0);
        when(forecastReadPort.findLatestMape()).thenReturn(new LatestMape(BigDecimal.valueOf(0.20), Instant.now()));

        ScPlannerDashboard result = useCase.assemble();

        assertThat(result.forecastAccuracy().status()).isEqualTo(MapeStatus.ABOVE_THRESHOLD);
    }

    @Test
    void assemble_mapeExactlyAtThreshold_returnsWithinThresholdStatus() {
        when(replenishmentReadPort.countPendingApprovals()).thenReturn(0);
        when(inventoryReadPort.countActiveAlerts()).thenReturn(0);
        // 0.15 == threshold → WITHIN_THRESHOLD (compareTo <= 0)
        when(forecastReadPort.findLatestMape()).thenReturn(new LatestMape(BigDecimal.valueOf(0.15), Instant.now()));

        ScPlannerDashboard result = useCase.assemble();

        assertThat(result.forecastAccuracy().status()).isEqualTo(MapeStatus.WITHIN_THRESHOLD);
    }

    @Test
    void assemble_mapeThresholdReturnedCorrectly() {
        when(replenishmentReadPort.countPendingApprovals()).thenReturn(1);
        when(inventoryReadPort.countActiveAlerts()).thenReturn(2);
        when(forecastReadPort.findLatestMape()).thenReturn(new LatestMape(BigDecimal.valueOf(0.08), Instant.now()));

        ScPlannerDashboard result = useCase.assemble();

        assertThat(result.forecastAccuracy().mapeThreshold()).isEqualByComparingTo(BigDecimal.valueOf(0.15));
    }
}
