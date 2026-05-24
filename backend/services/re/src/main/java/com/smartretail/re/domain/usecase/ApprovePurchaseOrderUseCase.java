package com.smartretail.re.domain.usecase;

import com.smartretail.re.domain.model.PurchaseOrder;
import com.smartretail.re.domain.model.WorkflowStatus;
import com.smartretail.re.domain.model.exception.InvalidStatusTransitionException;
import com.smartretail.re.domain.model.exception.OptimisticLockException;
import com.smartretail.re.domain.model.exception.PurchaseOrderNotFoundException;
import com.smartretail.re.port.inbound.ApprovePurchaseOrderPort;
import com.smartretail.re.port.outbound.PurchaseOrderEventPublisherPort;
import com.smartretail.re.port.outbound.ReplenishmentRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class ApprovePurchaseOrderUseCase implements ApprovePurchaseOrderPort {

    private static final Logger log = LoggerFactory.getLogger(ApprovePurchaseOrderUseCase.class);

    private final ReplenishmentRepositoryPort repo;
    private final PurchaseOrderEventPublisherPort publisher;

    public ApprovePurchaseOrderUseCase(ReplenishmentRepositoryPort repo,
                                       PurchaseOrderEventPublisherPort publisher) {
        this.repo = repo;
        this.publisher = publisher;
    }

    @Override
    public PurchaseOrder approve(UUID poId, int currentVersion, String approvedBy) {
        PurchaseOrder po = repo.findById(poId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(poId));

        if (!po.getWorkflowStatus().canApprove()) {
            throw new InvalidStatusTransitionException(po.getWorkflowStatus(), "approved");
        }

        int updated = repo.updateStatus(
                poId,
                WorkflowStatus.APPROVED,
                currentVersion,
                approvedBy,
                Instant.now(),
                null,
                null,
                null
        );

        if (updated == 0) {
            throw new OptimisticLockException(
                    "Concurrent modification detected on poId=" + poId
                    + " at version=" + currentVersion);
        }

        PurchaseOrder approved = repo.findById(poId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(poId));

        publisher.publishPurchaseOrderEvent(approved);

        log.info("PO approved: poId={} approvedBy={} version={}", poId, approvedBy, approved.getVersion());

        return approved;
    }
}
