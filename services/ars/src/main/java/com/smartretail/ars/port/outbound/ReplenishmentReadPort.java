package com.smartretail.ars.port.outbound;

import com.smartretail.ars.domain.model.ExecutiveDashboard.CycleTimeDataPoint;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ReplenishmentReadPort {
    Optional<BigDecimal> averageCycleTimeDays(int days);
    List<CycleTimeDataPoint> findWeeklyCycleTimeHistory(int days);

    /** Returns supplierId → [completed, total] for POs approved within the last {@code days} days. */
    Map<UUID, int[]> fillRateBySupplier(int days);
}
