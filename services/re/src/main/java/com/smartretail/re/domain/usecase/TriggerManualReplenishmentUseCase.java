package com.smartretail.re.domain.usecase;

import com.smartretail.re.domain.model.PoLineItem;
import com.smartretail.re.domain.model.PurchaseOrder;
import com.smartretail.re.domain.model.ReplenishmentRule;
import com.smartretail.re.domain.model.WorkflowStatus;
import com.smartretail.re.domain.model.exception.ReplenishmentRuleNotFoundException;
import com.smartretail.re.port.inbound.TriggerManualReplenishmentPort;
import com.smartretail.re.port.outbound.PurchaseOrderEventPublisherPort;
import com.smartretail.re.port.outbound.ReplenishmentRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Handles manual replenishment triggers from the SC Planner MFE.
 * Always creates a PENDING_APPROVAL order — manual triggers require human review.
 * Domain core: no AWS imports.
 */
@Service
@Transactional
public class TriggerManualReplenishmentUseCase implements TriggerManualReplenishmentPort {

    private static final Logger log = LoggerFactory.getLogger(TriggerManualReplenishmentUseCase.class);

    private final ReplenishmentRepositoryPort replenishmentRepo;
    private final PurchaseOrderEventPublisherPort publisher;

    public TriggerManualReplenishmentUseCase(ReplenishmentRepositoryPort replenishmentRepo,
                                             PurchaseOrderEventPublisherPort publisher) {
        this.replenishmentRepo = replenishmentRepo;
        this.publisher = publisher;
    }

    @Override
    public PurchaseOrder trigger(String skuId, String dcId, int quantity, String notes) {
        log.info("Manual replenishment triggered: skuId={} dcId={} quantity={}", skuId, dcId, quantity);

        ReplenishmentRule rule = replenishmentRepo
                .findActiveRule(skuId, dcId)
                .orElseThrow(() -> new ReplenishmentRuleNotFoundException(skuId, dcId));

        // Enforce minimum order quantity from the rule
        int effectiveQuantity = Math.max(quantity, rule.getMoq());
        BigDecimal totalValue = rule.getCostPerUnit().multiply(BigDecimal.valueOf(effectiveQuantity));

        // Manual triggers always go to PENDING_APPROVAL — planner must review
        PurchaseOrder po = PurchaseOrder.create(rule, effectiveQuantity, totalValue,
                WorkflowStatus.PENDING_APPROVAL, null);

        replenishmentRepo.savePurchaseOrder(po);

        PoLineItem lineItem = new PoLineItem(
                UUID.randomUUID(),
                po.getPoId(),
                skuId,
                effectiveQuantity,
                rule.getCostPerUnit(),
                totalValue
        );
        replenishmentRepo.saveLineItem(lineItem);

        publisher.publishPurchaseOrderEvent(po);

        log.info("Manual PO created: poId={} status={} totalValue={}", po.getPoId(),
                po.getWorkflowStatus(), totalValue);
        return po;
    }
}
