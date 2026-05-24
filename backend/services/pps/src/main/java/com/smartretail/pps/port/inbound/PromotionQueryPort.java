package com.smartretail.pps.port.inbound;

import com.smartretail.pps.domain.model.PromotionList;

/**
 * Inbound port for promotion schedule queries.
 */
public interface PromotionQueryPort {

    /**
     * Returns promotion schedules, optionally filtered by status.
     *
     * @param status optional filter (ACTIVE, EXPIRED, CANCELLED) — null returns all
     */
    PromotionList getPromotionSchedules(String status);
}
