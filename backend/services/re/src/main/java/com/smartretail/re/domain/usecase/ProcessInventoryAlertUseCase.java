package com.smartretail.re.domain.usecase;

import com.smartretail.re.domain.model.InventoryAlertEventDto;
import com.smartretail.re.domain.model.PoLineItem;
import com.smartretail.re.domain.model.PurchaseOrder;
import com.smartretail.re.domain.model.ReplenishmentRule;
import com.smartretail.re.domain.model.WorkflowStatus;
import com.smartretail.re.domain.model.exception.ReplenishmentRuleNotFoundException;
import com.smartretail.re.port.inbound.ProcessInventoryAlertPort;
import com.smartretail.re.port.outbound.PurchaseOrderEventPublisherPort;
import com.smartretail.re.port.outbound.ReplenishmentRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional
public class ProcessInventoryAlertUseCase implements ProcessInventoryAlertPort {

    private static final Logger log = LoggerFactory.getLogger(ProcessInventoryAlertUseCase.class);

    private final ReplenishmentRepositoryPort replenishmentRepo;
    private final PurchaseOrderEventPublisherPort publisher;

    public ProcessInventoryAlertUseCase(ReplenishmentRepositoryPort replenishmentRepo,
                                        PurchaseOrderEventPublisherPort publisher) {
        this.replenishmentRepo = replenishmentRepo;
        this.publisher = publisher;
    }

    @Override
    public void processInventoryAlert(InventoryAlertEventDto event) {
        log.info("InventoryAlertEvent received: skuId={} dcId={} thresholdValue={} actualValue={} alertId={}",
                event.skuId(), event.dcId(), event.thresholdValue(), event.actualValue(), event.alertId());

        ReplenishmentRule rule = replenishmentRepo
                .findActiveRule(event.skuId(), event.dcId())
                .orElseThrow(() -> new ReplenishmentRuleNotFoundException(event.skuId(), event.dcId()));

        log.info("Rule found: ruleId={} moq={} costPerUnit={} autoApproveThreshold={}",
                rule.getRuleId(), rule.getMoq(), rule.getCostPerUnit(), rule.getAutoApproveThreshold());

        // Quantity = max(reorderPoint - onHand, moq)
        // thresholdValue = reorderPoint, actualValue = onHand at alert time
        int gap = event.thresholdValue() - event.actualValue();
        int quantity = Math.max(gap, rule.getMoq());

        BigDecimal totalValue = rule.getCostPerUnit().multiply(BigDecimal.valueOf(quantity));

        WorkflowStatus status = totalValue.compareTo(rule.getAutoApproveThreshold()) <= 0
                ? WorkflowStatus.APPROVED
                : WorkflowStatus.PENDING_APPROVAL;

        log.info("Computed order: quantity={} totalValue={} status={}", quantity, totalValue, status);

        PurchaseOrder po = PurchaseOrder.create(
                rule,
                quantity,
                totalValue,
                status,
                UUID.fromString(event.alertId())
        );

        replenishmentRepo.savePurchaseOrder(po);

        PoLineItem lineItem = new PoLineItem(
                UUID.randomUUID(),
                po.getPoId(),
                event.skuId(),
                quantity,
                rule.getCostPerUnit(),
                totalValue
        );
        replenishmentRepo.saveLineItem(lineItem);

        publisher.publishPurchaseOrderEvent(po);

        log.info("PurchaseOrderEvent published: poId={} status={}", po.getPoId(), po.getWorkflowStatus());
    }
}
