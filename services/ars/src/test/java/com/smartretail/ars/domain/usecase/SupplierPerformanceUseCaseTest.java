package com.smartretail.ars.domain.usecase;

import com.smartretail.ars.domain.model.SupplierPerformanceDashboard;
import com.smartretail.ars.domain.model.SupplierPerformanceDashboard.SupplierEntry;
import com.smartretail.ars.port.outbound.ReplenishmentReadPort;
import com.smartretail.ars.port.outbound.ReplenishmentReadPort.PoMetricsRow;
import com.smartretail.ars.port.outbound.SupplierReadPort;
import com.smartretail.ars.port.outbound.SupplierReadPort.ShipmentMetricsRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierPerformanceUseCaseTest {

    @Mock private SupplierReadPort supplierReadPort;
    @Mock private ReplenishmentReadPort replenishmentReadPort;

    private SupplierPerformanceUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SupplierPerformanceUseCase(supplierReadPort, replenishmentReadPort);
    }

    @Test
    void assemble_returnsEntriesForAllSuppliers() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        stubPorts(
                Map.of(s1, "Alpha", s2, "Beta"),
                List.of(new PoMetricsRow(s1, 5, BigDecimal.valueOf(50000)),
                        new PoMetricsRow(s2, 3, BigDecimal.valueOf(30000))),
                List.of(new ShipmentMetricsRow(s1, 8, 10),
                        new ShipmentMetricsRow(s2, 4, 5)),
                Map.of(s1, BigDecimal.valueOf(1.5), s2, BigDecimal.valueOf(3.0)),
                Map.of(s1, 0, s2, 2)
        );

        SupplierPerformanceDashboard result = useCase.assemble();

        assertThat(result.suppliers()).hasSize(2);
        assertThat(result.dataFreshness()).isNotNull();
    }

    @Test
    void assemble_suppliersSortedByOtdRateAscending_worstFirst() {
        UUID high = UUID.randomUUID(); // 90% OTD
        UUID low  = UUID.randomUUID(); // 40% OTD
        stubPorts(
                Map.of(high, "GoodSupplier", low, "BadSupplier"),
                List.of(),
                List.of(new ShipmentMetricsRow(high, 9, 10),
                        new ShipmentMetricsRow(low,  4, 10)),
                Map.of(),
                Map.of()
        );

        SupplierPerformanceDashboard result = useCase.assemble();

        assertThat(result.suppliers()).extracting(SupplierEntry::supplierName)
                .containsExactly("BadSupplier", "GoodSupplier");
    }

    @Test
    void assemble_otdRateZero_whenNoShipments() {
        UUID id = UUID.randomUUID();
        stubPorts(
                Map.of(id, "NoShipmentsSupplier"),
                List.of(),
                List.of(new ShipmentMetricsRow(id, 0, 0)), // zero total → zero OTD rate
                Map.of(),
                Map.of()
        );

        SupplierPerformanceDashboard result = useCase.assemble();

        assertThat(result.suppliers().get(0).onTimeDeliveryRate())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void assemble_missingPoMetrics_defaultsToZero() {
        UUID id = UUID.randomUUID();
        stubPorts(
                Map.of(id, "MissingPO"),
                List.of(), // no PO metrics row
                List.of(new ShipmentMetricsRow(id, 5, 5)), // 5 on-time of 5 total = 100%
                Map.of(),
                Map.of()
        );

        SupplierPerformanceDashboard result = useCase.assemble();

        SupplierEntry entry = result.suppliers().get(0);
        assertThat(entry.totalPoCount()).isEqualTo(0);
        assertThat(entry.totalPoValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void assemble_openExceptionsIncluded() {
        UUID id = UUID.randomUUID();
        stubPorts(
                Map.of(id, "ExceptionSupplier"),
                List.of(),
                List.of(new ShipmentMetricsRow(id, 1, 2)), // 1 on-time of 2 total = 50%
                Map.of(),
                Map.of(id, 3)
        );

        SupplierPerformanceDashboard result = useCase.assemble();

        assertThat(result.suppliers().get(0).openExceptions()).isEqualTo(3);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubPorts(Map<UUID, String> names,
                            List<PoMetricsRow> poRows,
                            List<ShipmentMetricsRow> shipRows,
                            Map<UUID, BigDecimal> variances,
                            Map<UUID, Integer> exceptions) {
        when(supplierReadPort.findActiveSupplierNames()).thenReturn(names);
        when(replenishmentReadPort.findPoMetricsBySupplierId(90)).thenReturn(poRows);
        when(supplierReadPort.findShipmentMetricsBySupplierId()).thenReturn(shipRows);
        when(supplierReadPort.findAvgLeadTimeVarianceBySupplierId()).thenReturn(variances);
        when(supplierReadPort.findOpenExceptionsBySupplierId()).thenReturn(exceptions);
    }
}
