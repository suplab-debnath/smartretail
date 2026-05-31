package com.smartretail.pps.port.inbound;

import com.smartretail.pps.domain.model.PromotionActivationCommand;

/**
 * Inbound port — called by the SQS adapter when a PromotionActivated event arrives.
 * Implemented by PromotionActivationUseCase.
 */
public interface PromotionActivationPort {

    void activate(PromotionActivationCommand command);
}
