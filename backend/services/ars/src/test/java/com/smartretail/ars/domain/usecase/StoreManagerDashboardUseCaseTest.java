package com.smartretail.ars.domain.usecase;

import com.smartretail.ars.domain.model.StoreManagerDashboard;
import com.smartretail.ars.domain.model.StoreManagerDashboard.AlertKpi;
import com.smartretail.ars.domain.model.StoreManagerDashboard.AlertSummary;
import com.smartretail.ars.port.outbound.ForecastReadPort;
import com.smartretail.ars.port.outbound.InventoryReadPort;
import com.smartretail.ars.port.outbound.ReplenishmentReadPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoreManagerDashboardUseCaseTest {

    @Mock private InventoryReadPort inventoryReadPort;
    @Mock private ReplenishmentReadPort replenishmentReadPort;
    @Mock private ForecastReadPort forecastReadPort;

    private StoreManagerDashboardUseCase useCase;

    private static final String DC_ID = "DC-LONDON";

    @BeforeEach
    void setUp() {
        useCase = new StoreManagerDashboardUseCase(inventoryReadPort, replenishmentReadPort, forecastReadPort);
    }

    @Test
    void assemble_returnsCorrectDcIdAndCounts() {
        stubPorts(3, 0, List.of(), 500L, 20, 2, 15);

        StoreManagerDashboard result = useCase.assemble(DC_ID, 0, 10);

        assertThat(result.dcId()).isEqualTo(DC_ID);
        assertThat(result.pendingReplenishmentCount()).isEqualTo(2);
        assertThat(result.totalOnHandUnits()).isEqualTo(500L);
        assertThat(result.dataFreshness()).isNotNull();
    }

    @Test
    void assemble_forecastCoveragePct_calculatedCorrectly() {
        stubPorts(0, 0, List.of(), 1000L, 20, 0, 16);
        // 16 out of 20 = 80.0%

        StoreManagerDashboard result = useCase.assemble(DC_ID, 0, 10);

        assertThat(result.forecastCoveragePct()).isEqualByComparingTo(new BigDecimal("80.0"));
    }

    @Test
    void assemble_forecastCoverageZero_whenNoSkus() {
        stubPorts(0, 0, List.of(), 0L, 0, 0, 0);

        StoreManagerDashboard result = useCase.assemble(DC_ID, 0, 10);

        assertThat(result.forecastCoveragePct()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void assemble_paginationCalculatedCorrectly() {
        stubPorts(25, 25, List.of(), 100L, 10, 0, 8);
        // page=0, size=10, total=25 → totalPages=3

        StoreManagerDashboard result = useCase.assemble(DC_ID, 0, 10);

        assertThat(result.alertsPage()).isEqualTo(0);
        assertThat(result.alertsTotalPages()).isEqualTo(3);
    }

    @Test
    void assemble_alertsIncluded() {
        AlertSummary alert = new AlertSummary(UUID.randomUUID(), "SKU-001", DC_ID,
                "LOW_STOCK", "HIGH", 50, 100, Instant.now());
        stubPorts(1, 1, List.of(alert), 200L, 5, 1, 4);

        StoreManagerDashboard result = useCase.assemble(DC_ID, 0, 10);

        assertThat(result.alerts()).hasSize(1);
        assertThat(result.alerts().getFirst().skuId()).isEqualTo("SKU-001");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubPorts(int alertKpiCritical, int alertTotal,
                            List<AlertSummary> alerts, long onHand,
                            int totalSkus, int pendingPo, int skusWithForecast) {
        when(inventoryReadPort.countActiveAlertsByDc(DC_ID))
                .thenReturn(new AlertKpi(alertKpiCritical, 0, 0));
        when(inventoryReadPort.countActiveAlertsByDcTotal(DC_ID)).thenReturn(alertTotal);
        when(inventoryReadPort.findActiveAlertsByDc(anyString(), anyInt(), anyInt())).thenReturn(alerts);
        when(inventoryReadPort.sumOnHandByDc(DC_ID)).thenReturn(onHand);
        when(inventoryReadPort.countDistinctSkusByDc(DC_ID)).thenReturn(totalSkus);
        when(replenishmentReadPort.countPendingApprovalsByDc(DC_ID)).thenReturn(pendingPo);
        when(forecastReadPort.countSkusWithForecastByDc(DC_ID)).thenReturn(skusWithForecast);
    }
}
