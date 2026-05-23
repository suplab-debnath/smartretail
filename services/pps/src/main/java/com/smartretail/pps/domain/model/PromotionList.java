package com.smartretail.pps.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Domain model for the promotion schedule list.
 * Data sourced entirely from promotions schema — no cross-schema joins.
 */
public record PromotionList(
        List<PromotionSchedule> schedules,
        Instant dataFreshness) {

    public record PromotionSchedule(
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
