package com.smartretail.ars.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StoreManagerDashboard(
        String dcId,
        AlertKpi alertKpi,
        long totalOnHandUnits,
        int pendingReplenishmentCount,
        BigDecimal forecastCoveragePct,
        List<AlertSummary> alerts,
        int alertsPage,
        int alertsTotalPages,
        Instant dataFreshness
) {
    public record AlertKpi(int criticalCount, int highCount, int mediumCount) {
        public int totalActive() {
            return criticalCount + highCount + mediumCount;
        }
    }

    public record AlertSummary(
            UUID alertId,
            String skuId,
            String dcId,
            String alertType,
            String severity,
            int onHand,
            int reorderPoint,
            Instant raisedAt
    ) {}
}
