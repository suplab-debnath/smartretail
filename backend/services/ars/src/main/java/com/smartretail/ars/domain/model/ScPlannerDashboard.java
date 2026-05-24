package com.smartretail.ars.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Pure domain record — zero infrastructure imports. */
public record ScPlannerDashboard(
        int pendingApprovalCount,
        int activeAlertCount,
        ForecastAccuracy forecastAccuracy,
        Instant dataFreshness) {

    public record ForecastAccuracy(
            BigDecimal latestMape,
            BigDecimal mapeThreshold,
            Instant lastRunAt,
            MapeStatus status) {}

    public enum MapeStatus {
        WITHIN_THRESHOLD,
        ABOVE_THRESHOLD
    }
}
