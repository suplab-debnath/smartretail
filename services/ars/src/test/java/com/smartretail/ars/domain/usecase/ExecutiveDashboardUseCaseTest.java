package com.smartretail.ars.domain.usecase;

import com.smartretail.ars.domain.model.ExecutiveDashboard;
import com.smartretail.ars.domain.model.ExecutiveDashboard.*;
import com.smartretail.ars.port.outbound.ForecastReadPort;
import com.smartretail.ars.port.outbound.InventoryReadPort;
import com.smartretail.ars.port.outbound.ReplenishmentReadPort;
import com.smartretail.ars.port.outbound.SupplierReadPort;
import com.smartretail.ars.port.outbound.SupplierReadPort.SupplierDeliveryStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutiveDashboardUseCaseTest {

    @Mock private ForecastReadPort forecastReadPort;
    @Mock private InventoryReadPort inventoryReadPort;
    @Mock private ReplenishmentReadPort replenishmentReadPort;
    @Mock private SupplierReadPort supplierReadPort;

    private ExecutiveDashboardUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ExecutiveDashboardUseCase(
                forecastReadPort, inventoryReadPort, replenishmentReadPort, supplierReadPort);
    }

    @Test
    void assemble_withFullData_returnsPopulatedDashboard() {
        UUID supplierId = UUID.randomUUID();
        stubAllPorts(supplierId);

        ExecutiveDashboard result = useCase.assemble();

        assertThat(result).isNotNull();
        assertThat(result.forecastAccuracy()).isNotNull();
        assertThat(result.stockoutFrequency().last30Days()).isEqualTo(5);
        assertThat(result.onTimeDelivery().rate()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(result.supplierPerformance()).hasSize(1);
        assertThat(result.dataFreshness()).isNotNull();
    }

    @Test
    void assemble_mapeImproving_whenRecentLowerThanPrior() {
        // Build 14 data points: first 7 (recent) lower MAPE than next 7 (prior)
        List<MapeDataPoint> history = buildMapeHistory(7, 0.05, 7, 0.12);
        when(forecastReadPort.findRecentMapeHistory(30)).thenReturn(history);
        stubNonForecastPorts();

        ExecutiveDashboard result = useCase.assemble();

        assertThat(result.forecastAccuracy().trend()).isEqualTo(Trend.IMPROVING);
    }

    @Test
    void assemble_mapeDegrading_whenRecentHigherThanPrior() {
        List<MapeDataPoint> history = buildMapeHistory(7, 0.15, 7, 0.05);
        when(forecastReadPort.findRecentMapeHistory(30)).thenReturn(history);
        stubNonForecastPorts();

        ExecutiveDashboard result = useCase.assemble();

        assertThat(result.forecastAccuracy().trend()).isEqualTo(Trend.DEGRADING);
    }

    @Test
    void assemble_mapeStable_whenHistoryTooShort() {
        when(forecastReadPort.findRecentMapeHistory(30)).thenReturn(List.of());
        stubNonForecastPorts();

        ExecutiveDashboard result = useCase.assemble();

        assertThat(result.forecastAccuracy().trend()).isEqualTo(Trend.STABLE);
        assertThat(result.forecastAccuracy().latestMape()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void assemble_stockoutIncreasing_whenLast30HigherThanPrior30() {
        when(forecastReadPort.findRecentMapeHistory(30)).thenReturn(List.of());
        when(inventoryReadPort.countCriticalAlerts(30)).thenReturn(10);
        when(inventoryReadPort.countCriticalAlerts(60)).thenReturn(15); // prior30 = 15-10 = 5
        when(inventoryReadPort.findDailyCriticalAlertHistory(30)).thenReturn(List.of());
        when(replenishmentReadPort.averageCycleTimeDays(90)).thenReturn(Optional.empty());
        when(replenishmentReadPort.findWeeklyCycleTimeHistory(90)).thenReturn(List.of());
        when(supplierReadPort.findDeliveryStats()).thenReturn(List.of());
        when(replenishmentReadPort.fillRateBySupplier(90)).thenReturn(Map.of());
        when(supplierReadPort.findActiveSupplierNames()).thenReturn(Map.of());

        ExecutiveDashboard result = useCase.assemble();

        assertThat(result.stockoutFrequency().trend()).isEqualTo(DirectionTrend.INCREASING);
    }

    @Test
    void assemble_onTimeDelivery_calculatedCorrectly() {
        UUID supplierId = UUID.randomUUID();
        when(forecastReadPort.findRecentMapeHistory(30)).thenReturn(List.of());
        when(inventoryReadPort.countCriticalAlerts(anyInt())).thenReturn(0);
        when(inventoryReadPort.findDailyCriticalAlertHistory(30)).thenReturn(List.of());
        when(replenishmentReadPort.averageCycleTimeDays(90)).thenReturn(Optional.of(BigDecimal.valueOf(5)));
        when(replenishmentReadPort.findWeeklyCycleTimeHistory(90)).thenReturn(List.of());
        // 8 on-time (3 early + 5 onTime), 2 late → 80% OTD
        when(supplierReadPort.findDeliveryStats()).thenReturn(
                List.of(new SupplierDeliveryStats(supplierId, 3, 5, 2)));
        when(replenishmentReadPort.fillRateBySupplier(90)).thenReturn(
                Map.of(supplierId, new int[]{80, 100}));
        when(supplierReadPort.findActiveSupplierNames()).thenReturn(
                Map.of(supplierId, "Supplier A"));

        ExecutiveDashboard result = useCase.assemble();

        assertThat(result.onTimeDelivery().rate()).isEqualByComparingTo(new BigDecimal("0.8000"));
    }

    @Test
    void assemble_suppliersSortedByOtdDescending() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        when(forecastReadPort.findRecentMapeHistory(30)).thenReturn(List.of());
        when(inventoryReadPort.countCriticalAlerts(anyInt())).thenReturn(0);
        when(inventoryReadPort.findDailyCriticalAlertHistory(30)).thenReturn(List.of());
        when(replenishmentReadPort.averageCycleTimeDays(90)).thenReturn(Optional.empty());
        when(replenishmentReadPort.findWeeklyCycleTimeHistory(90)).thenReturn(List.of());
        when(supplierReadPort.findDeliveryStats()).thenReturn(List.of(
                new SupplierDeliveryStats(s1, 0, 6, 4), // 60% OTD
                new SupplierDeliveryStats(s2, 0, 9, 1)  // 90% OTD
        ));
        when(replenishmentReadPort.fillRateBySupplier(90)).thenReturn(Map.of());
        when(supplierReadPort.findActiveSupplierNames()).thenReturn(Map.of(s1, "Low", s2, "High"));

        ExecutiveDashboard result = useCase.assemble();

        assertThat(result.supplierPerformance()).extracting(SupplierPerformanceEntry::supplierName)
                .containsExactly("High", "Low");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubAllPorts(UUID supplierId) {
        when(forecastReadPort.findRecentMapeHistory(30)).thenReturn(List.of());
        when(inventoryReadPort.countCriticalAlerts(anyInt())).thenReturn(5);
        when(inventoryReadPort.findDailyCriticalAlertHistory(30)).thenReturn(List.of());
        when(replenishmentReadPort.averageCycleTimeDays(90)).thenReturn(Optional.of(BigDecimal.valueOf(3.5)));
        when(replenishmentReadPort.findWeeklyCycleTimeHistory(90)).thenReturn(List.of());
        when(supplierReadPort.findDeliveryStats()).thenReturn(
                List.of(new SupplierDeliveryStats(supplierId, 2, 7, 1)));
        when(replenishmentReadPort.fillRateBySupplier(90)).thenReturn(
                Map.of(supplierId, new int[]{90, 100}));
        when(supplierReadPort.findActiveSupplierNames()).thenReturn(
                Map.of(supplierId, "Test Supplier"));
    }

    private void stubNonForecastPorts() {
        when(inventoryReadPort.countCriticalAlerts(anyInt())).thenReturn(0);
        when(inventoryReadPort.findDailyCriticalAlertHistory(30)).thenReturn(List.of());
        when(replenishmentReadPort.averageCycleTimeDays(90)).thenReturn(Optional.empty());
        when(replenishmentReadPort.findWeeklyCycleTimeHistory(90)).thenReturn(List.of());
        when(supplierReadPort.findDeliveryStats()).thenReturn(List.of());
        when(replenishmentReadPort.fillRateBySupplier(90)).thenReturn(Map.of());
        when(supplierReadPort.findActiveSupplierNames()).thenReturn(Map.of());
    }

    private List<MapeDataPoint> buildMapeHistory(int recentCount, double recentMape,
                                                  int priorCount, double priorMape) {
        var recent = new java.util.ArrayList<MapeDataPoint>();
        for (int i = 0; i < recentCount; i++) {
            recent.add(new MapeDataPoint(LocalDate.now().minusDays(i), BigDecimal.valueOf(recentMape)));
        }
        for (int i = 0; i < priorCount; i++) {
            recent.add(new MapeDataPoint(LocalDate.now().minusDays(recentCount + i),
                    BigDecimal.valueOf(priorMape)));
        }
        return recent;
    }
}
