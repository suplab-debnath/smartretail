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

@Service
public class ScPlannerDashboardUseCase implements ScPlannerDashboardPort {

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
        // Sequential reads — free-tier RDS has limited connections; one connection reused per request
        int pendingCount  = replenishmentReadPort.countPendingApprovals();
        int alertCount    = inventoryReadPort.countActiveAlerts();
        LatestMape latest = forecastReadPort.findLatestMape();

        MapeStatus status = latest.mape().compareTo(MAPE_THRESHOLD) > 0
                ? MapeStatus.ABOVE_THRESHOLD
                : MapeStatus.WITHIN_THRESHOLD;

        ForecastAccuracy forecastAccuracy = new ForecastAccuracy(
                latest.mape(), MAPE_THRESHOLD, latest.lastRunAt(), status);

        return new ScPlannerDashboard(pendingCount, alertCount, forecastAccuracy, Instant.now());
    }
}
