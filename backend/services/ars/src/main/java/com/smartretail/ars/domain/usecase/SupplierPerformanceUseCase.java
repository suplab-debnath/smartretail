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
import java.util.concurrent.CompletableFuture;
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
        // Three parallel reads — no cross-schema SQL joins (Architecture rule #1)
        CompletableFuture<Map<UUID, String>> namesFuture =
                CompletableFuture.supplyAsync(supplierReadPort::findActiveSupplierNames);

        CompletableFuture<List<PoMetricsRow>> poFuture =
                CompletableFuture.supplyAsync(() -> replenishmentReadPort.findPoMetricsBySupplierId(LOOKBACK_DAYS));

        CompletableFuture<List<ShipmentMetricsRow>> shipFuture =
                CompletableFuture.supplyAsync(supplierReadPort::findShipmentMetricsBySupplierId);

        CompletableFuture<Map<UUID, BigDecimal>> varianceFuture =
                CompletableFuture.supplyAsync(supplierReadPort::findAvgLeadTimeVarianceBySupplierId);

        CompletableFuture<Map<UUID, Integer>> exceptionsFuture =
                CompletableFuture.supplyAsync(supplierReadPort::findOpenExceptionsBySupplierId);

        CompletableFuture.allOf(namesFuture, poFuture, shipFuture, varianceFuture, exceptionsFuture).join();

        Map<UUID, String>       names      = namesFuture.join();
        Map<UUID, PoMetricsRow> poMetrics  = poFuture.join().stream()
                .collect(Collectors.toMap(PoMetricsRow::supplierId, r -> r));
        Map<UUID, ShipmentMetricsRow> shipMetrics = shipFuture.join().stream()
                .collect(Collectors.toMap(ShipmentMetricsRow::supplierId, r -> r));
        Map<UUID, BigDecimal>   variances  = varianceFuture.join();
        Map<UUID, Integer>      exceptions = exceptionsFuture.join();

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
