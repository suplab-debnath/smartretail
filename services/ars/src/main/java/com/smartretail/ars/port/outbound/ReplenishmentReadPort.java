package com.smartretail.ars.port.outbound;

import com.smartretail.ars.domain.model.ExecutiveDashboard.CycleTimeDataPoint;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ReplenishmentReadPort {
    /** Average cycle time days for the Executive dashboard. */
    Optional<BigDecimal> averageCycleTimeDays(int days);

    /** Weekly cycle time history for the Executive dashboard. */
    List<CycleTimeDataPoint> findWeeklyCycleTimeHistory(int days);

    /** supplierId → [completed, total] for Executive supplier fill-rate. */
    Map<UUID, int[]> fillRateBySupplier(int days);

    /** Count of PENDING_APPROVAL POs — used by SC Planner dashboard badge. */
    int countPendingApprovals();

    /**
     * PO count and total value per supplier from replenishment schema only.
     * No join to supplier schema — merged in Java.
     */
    List<PoMetricsRow> findPoMetricsBySupplierId(int days);

    record PoMetricsRow(UUID supplierId, int totalPoCount, BigDecimal totalPoValue) {}
}
