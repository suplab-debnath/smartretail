package com.smartretail.ars.domain.usecase;

import com.smartretail.ars.domain.model.StoreManagerDashboard;
import com.smartretail.ars.domain.model.StoreManagerDashboard.AlertKpi;
import com.smartretail.ars.domain.model.StoreManagerDashboard.AlertSummary;
import com.smartretail.ars.port.inbound.StoreManagerDashboardPort;
import com.smartretail.ars.port.outbound.ForecastReadPort;
import com.smartretail.ars.port.outbound.InventoryReadPort;
import com.smartretail.ars.port.outbound.ReplenishmentReadPort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class StoreManagerDashboardUseCase implements StoreManagerDashboardPort {

    private final InventoryReadPort inventoryReadPort;
    private final ReplenishmentReadPort replenishmentReadPort;
    private final ForecastReadPort forecastReadPort;

    public StoreManagerDashboardUseCase(
            InventoryReadPort inventoryReadPort,
            ReplenishmentReadPort replenishmentReadPort,
            ForecastReadPort forecastReadPort) {
        this.inventoryReadPort = inventoryReadPort;
        this.replenishmentReadPort = replenishmentReadPort;
        this.forecastReadPort = forecastReadPort;
    }

    @Override
    public StoreManagerDashboard assemble(String dcId, int page, int size) {
        // Parallel reads — each query is confined to its own schema (Architecture rule #1)
        CompletableFuture<AlertKpi> alertKpiFuture =
                CompletableFuture.supplyAsync(() -> inventoryReadPort.countActiveAlertsByDc(dcId));

        CompletableFuture<Integer> alertTotalFuture =
                CompletableFuture.supplyAsync(() -> inventoryReadPort.countActiveAlertsByDcTotal(dcId));

        CompletableFuture<List<AlertSummary>> alertsFuture =
                CompletableFuture.supplyAsync(() -> inventoryReadPort.findActiveAlertsByDc(dcId, page, size));

        CompletableFuture<Long> onHandFuture =
                CompletableFuture.supplyAsync(() -> inventoryReadPort.sumOnHandByDc(dcId));

        CompletableFuture<Integer> totalSkusFuture =
                CompletableFuture.supplyAsync(() -> inventoryReadPort.countDistinctSkusByDc(dcId));

        CompletableFuture<Integer> pendingPoFuture =
                CompletableFuture.supplyAsync(() -> replenishmentReadPort.countPendingApprovalsByDc(dcId));

        CompletableFuture<Integer> skusWithForecastFuture =
                CompletableFuture.supplyAsync(() -> forecastReadPort.countSkusWithForecastByDc(dcId));

        CompletableFuture.allOf(
                alertKpiFuture, alertTotalFuture, alertsFuture,
                onHandFuture, totalSkusFuture, pendingPoFuture, skusWithForecastFuture
        ).join();

        int totalAlerts = alertTotalFuture.join();
        int totalPages  = size > 0 ? (int) Math.ceil((double) totalAlerts / size) : 0;

        int totalSkus         = totalSkusFuture.join();
        int skusWithForecast  = skusWithForecastFuture.join();
        BigDecimal forecastCovPct = totalSkus > 0
                ? BigDecimal.valueOf(100.0 * skusWithForecast / totalSkus).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new StoreManagerDashboard(
                dcId,
                alertKpiFuture.join(),
                onHandFuture.join(),
                pendingPoFuture.join(),
                forecastCovPct,
                alertsFuture.join(),
                page,
                totalPages,
                Instant.now()
        );
    }
}
