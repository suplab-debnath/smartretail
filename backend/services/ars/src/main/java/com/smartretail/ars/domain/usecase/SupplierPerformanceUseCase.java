package com.smartretail.ars.domain.usecase;

import com.smartretail.ars.domain.model.SupplierPerformanceDashboard;
import com.smartretail.ars.domain.model.SupplierPerformanceDashboard.SupplierEntry;
import com.smartretail.ars.port.inbound.SupplierPerformancePort;
import com.smartretail.ars.port.outbound.ReplenishmentReadPort;
import com.smartretail.ars.port.outbound.ReplenishmentReadPort.PoMetricsRow;
import com.smartretail.ars.port.outbound.SupplierReadPort;
import com.smartretail.ars.port.outbound.SupplierReadPort.ShipmentMetricsRow;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SupplierPerformanceUseCase implements SupplierPerformancePort {

    private static final int LOOKBACK_DAYS = 90;

    private final SupplierReadPort supplierReadPort;
    private final ReplenishmentReadPort replenishmentReadPort;

    public SupplierPerformanceUseCase(
            SupplierReadPort supplierReadPort,
            ReplenishmentReadPort replenishmentReadPort) {
        this.supplierReadPort = supplierReadPort;
        this.replenishmentReadPort = replenishmentReadPort;
    }

    @Override
    public SupplierPerformanceDashboard assemble() {
        // Sequential reads — free-tier RDS has limited connections; one connection reused per request
        Map<UUID, String>       names      = supplierReadPort.findActiveSupplierNames();
        Map<UUID, PoMetricsRow> poMetrics  = replenishmentReadPort.findPoMetricsBySupplierId(LOOKBACK_DAYS).stream()
                .collect(Collectors.toMap(PoMetricsRow::supplierId, r -> r));
        Map<UUID, ShipmentMetricsRow> shipMetrics = supplierReadPort.findShipmentMetricsBySupplierId().stream()
                .collect(Collectors.toMap(ShipmentMetricsRow::supplierId, r -> r));
        Map<UUID, BigDecimal>   variances  = supplierReadPort.findAvgLeadTimeVarianceBySupplierId();
        Map<UUID, Integer>      exceptions = supplierReadPort.findOpenExceptionsBySupplierId();

        // Merge in Java — supplierId is the join key, never SQL
        List<SupplierEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, String> nameEntry : names.entrySet()) {
            UUID   id   = nameEntry.getKey();
            String name = nameEntry.getValue();

            PoMetricsRow      po   = poMetrics.getOrDefault(id, new PoMetricsRow(id, 0, BigDecimal.ZERO));
            ShipmentMetricsRow ship = shipMetrics.getOrDefault(id, new ShipmentMetricsRow(id, 0, 0));

            BigDecimal otdRate = ship.totalShipped() == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(ship.onTimeCount())
                            .divide(BigDecimal.valueOf(ship.totalShipped()), 4, RoundingMode.HALF_UP);

            // SLA compliance: reuse OTD rate as proxy (no separate SLA query in seed data)
            BigDecimal slaBD = otdRate;

            BigDecimal variance  = variances.getOrDefault(id, BigDecimal.ZERO);
            int        openExc   = exceptions.getOrDefault(id, 0);

            entries.add(new SupplierEntry(
                    id, name, otdRate, slaBD, openExc,
                    variance.setScale(2, RoundingMode.HALF_UP),
                    po.totalPoCount(),
                    po.totalPoValue().setScale(2, RoundingMode.HALF_UP)
            ));
        }

        // Sort worst OTD first so planners see problem suppliers at top
        entries.sort(Comparator.comparing(SupplierEntry::onTimeDeliveryRate));

        return new SupplierPerformanceDashboard(entries, Instant.now());
    }
}
