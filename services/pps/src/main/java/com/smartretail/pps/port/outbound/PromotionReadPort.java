package com.smartretail.pps.port.outbound;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port for promotion schedule read queries.
 * All SQL is confined to the promotions schema — no cross-schema JOINs.
 */
public interface PromotionReadPort {

    /**
     * Returns promotion schedules sorted by validFrom ascending.
     *
     * @param status optional filter — null returns all statuses
     */
    List<PromotionRow> findPromotionSchedules(String status);

    /**
     * Returns MAX(created_at) across all promotion schedules for data freshness.
     */
    Instant findDataFreshness();

    record PromotionRow(
            UUID promotionId,
            String promotionName,
            List<String> skuIds,
            List<String> dcIds,
            double discountPct,
            double upliftFactor,
            Double elasticityCoeff,
            LocalDate validFrom,
            LocalDate validTo,
            String status,
            UUID sourceEventId) {}
}
