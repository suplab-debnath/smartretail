package com.smartretail.pps.port.outbound;

import com.smartretail.pps.domain.model.PromotionActivationCommand;

/**
 * Outbound port — write side of promotions.promotion_schedules.
 * Implemented by PromotionWriteRepository.
 */
public interface PromotionWritePort {

    /**
     * Upsert a promotion schedule row. ON CONFLICT (promotion_id) DO UPDATE ensures
     * idempotent handling of re-delivered events.
     */
    void upsert(PromotionActivationCommand command);
}
