package com.smartretail.pps.domain.usecase;

import com.smartretail.pps.domain.model.PromotionActivationCommand;
import com.smartretail.pps.port.inbound.PromotionActivationPort;
import com.smartretail.pps.port.outbound.PromotionWritePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service: persists an incoming PromotionActivated event.
 * Pure domain logic — no AWS imports.
 */
@Service
@Transactional
public class PromotionActivationUseCase implements PromotionActivationPort {

    private static final Logger log = LoggerFactory.getLogger(PromotionActivationUseCase.class);

    private final PromotionWritePort writePort;

    public PromotionActivationUseCase(PromotionWritePort writePort) {
        this.writePort = writePort;
    }

    @Override
    public void activate(PromotionActivationCommand command) {
        log.info("Activating promotion promotionId={} skus={} discount={}%",
                command.promotionId(), command.skuIds().size(), command.discountPct());
        writePort.upsert(command);
        log.info("Promotion persisted promotionId={}", command.promotionId());
    }
}
