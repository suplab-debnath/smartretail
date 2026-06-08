package com.smartretail.ars.domain.usecase;

import com.smartretail.ars.domain.model.ScPlannerDashboard;
import com.smartretail.ars.domain.model.ScPlannerDashboard.ForecastAccuracy;
import com.smartretail.ars.domain.model.ScPlannerDashboard.MapeStatus;
import com.smartretail.ars.port.inbound.ScPlannerDashboardPort;
import com.smartretail.ars.port.outbound.ForecastReadPort;
import com.smartretail.ars.port.outbound.ForecastReadPort.LatestMape;
import com.smartretail.ars.port.outbound.InventoryReadPort;
import com.smartretail.ars.port.outbound.ReplenishmentReadPort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Service
public class ScPlannerDashboardUseCase implements ScPlannerDashboardPort {

    private static final Executor VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();
    private static final BigDecimal MAPE_THRESHOLD = BigDecimal.valueOf(0.15);

    private final ForecastReadPort forecastReadPort;
    private final InventoryReadPort inventoryReadPort;
    private final ReplenishmentReadPort replenishmentReadPort;

    public ScPlannerDashboardUseCase(
            ForecastReadPort forecastReadPort,
            InventoryReadPort inventoryReadPort,
            ReplenishmentReadPort replenishmentReadPort) {
        this.forecastReadPort = forecastReadPort;
        this.inventoryReadPort = inventoryReadPort;
        this.replenishmentReadPort = replenishmentReadPort;
    }

    @Override
    public ScPlannerDashboard assemble() {
        // Parallel reads — each query targets a single schema (Architecture rule #1).
        CompletableFuture<Integer>   pendingF = CompletableFuture.supplyAsync(replenishmentReadPort::countPendingApprovals, VIRTUAL);
        CompletableFuture<Integer>   alertF   = CompletableFuture.supplyAsync(inventoryReadPort::countActiveAlerts, VIRTUAL);
        CompletableFuture<LatestMape> mapeF   = CompletableFuture.supplyAsync(forecastReadPort::findLatestMape, VIRTUAL);

        CompletableFuture.allOf(pendingF, alertF, mapeF).join();

        int pendingCount  = pendingF.join();
        int alertCount    = alertF.join();
        LatestMape latest = mapeF.join();

        MapeStatus status = latest.mape().compareTo(MAPE_THRESHOLD) > 0
                ? MapeStatus.ABOVE_THRESHOLD
                : MapeStatus.WITHIN_THRESHOLD;

        ForecastAccuracy forecastAccuracy = new ForecastAccuracy(
                latest.mape(), MAPE_THRESHOLD, latest.lastRunAt(), status);

        return new ScPlannerDashboard(pendingCount, alertCount, forecastAccuracy, Instant.now());
    }
}
