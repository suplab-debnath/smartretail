package com.smartretail.re.domain.usecase;

import com.smartretail.re.domain.model.PurchaseOrder;
import com.smartretail.re.domain.model.WorkflowStatus;
import com.smartretail.re.domain.model.exception.InvalidStatusTransitionException;
import com.smartretail.re.domain.model.exception.OptimisticLockException;
import com.smartretail.re.domain.model.exception.PurchaseOrderNotFoundException;
import com.smartretail.re.port.inbound.RejectPurchaseOrderPort;
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
public class RejectPurchaseOrderUseCase implements RejectPurchaseOrderPort {

    private static final Logger log = LoggerFactory.getLogger(RejectPurchaseOrderUseCase.class);

    private final ReplenishmentRepositoryPort repo;
    private final PurchaseOrderEventPublisherPort publisher;

    public RejectPurchaseOrderUseCase(ReplenishmentRepositoryPort repo,
                                      PurchaseOrderEventPublisherPort publisher) {
        this.repo = repo;
        this.publisher = publisher;
    }

    @Override
    public PurchaseOrder reject(UUID poId, int currentVersion, String rejectedBy, String rejectionReason) {
        PurchaseOrder po = repo.findById(poId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(poId));

        if (!po.getWorkflowStatus().canReject()) {
            throw new InvalidStatusTransitionException(po.getWorkflowStatus(), "rejected");
        }

        int updated = repo.updateStatus(
                poId,
                WorkflowStatus.REJECTED,
                currentVersion,
                null,
                null,
                rejectedBy,
                Instant.now(),
                rejectionReason
        );

        if (updated == 0) {
            throw new OptimisticLockException(
                    "Concurrent modification detected on poId=" + poId
                    + " at version=" + currentVersion);
        }

        PurchaseOrder rejected = repo.findById(poId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(poId));

        publisher.publishPurchaseOrderEvent(rejected);

        log.info("PO rejected: poId={} rejectedBy={} version={}", poId, rejectedBy, rejected.getVersion());

        return rejected;
    }
}
