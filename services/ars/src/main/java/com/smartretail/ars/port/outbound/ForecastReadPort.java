package com.smartretail.ars.port.outbound;

import com.smartretail.ars.domain.model.ExecutiveDashboard.MapeDataPoint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface ForecastReadPort {
    /** Returns up to {@code limit} COMPLETED forecast runs, newest first. */
    List<MapeDataPoint> findRecentMapeHistory(int limit);

    /** Returns the MAPE and run timestamp of the latest COMPLETED forecast run. */
    LatestMape findLatestMape();

    record LatestMape(BigDecimal mape, Instant lastRunAt) {}

    // ── Store Manager methods (scoped to a single DC) ──────────────────────────

    /** Count of distinct SKUs with at least one forecast in the next 7 days for {@code dcId}. */
    int countSkusWithForecastByDc(String dcId);
}
