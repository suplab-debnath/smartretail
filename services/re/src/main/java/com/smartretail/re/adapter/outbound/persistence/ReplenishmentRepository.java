package com.smartretail.re.adapter.outbound.persistence;

import com.smartretail.re.domain.model.PoLineItem;
import com.smartretail.re.domain.model.PurchaseOrder;
import com.smartretail.re.domain.model.ReplenishmentRule;
import com.smartretail.re.domain.model.WorkflowStatus;
import com.smartretail.re.port.outbound.ReplenishmentRepositoryPort;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReplenishmentRepository implements ReplenishmentRepositoryPort {

    private final ReplenishmentRuleJpaRepository ruleRepo;
    private final PurchaseOrderJpaRepository poRepo;
    private final PoLineItemJpaRepository lineRepo;

    public ReplenishmentRepository(ReplenishmentRuleJpaRepository ruleRepo,
            PurchaseOrderJpaRepository poRepo,
            PoLineItemJpaRepository lineRepo) {
        this.ruleRepo = ruleRepo;
        this.poRepo = poRepo;
        this.lineRepo = lineRepo;
    }

    @Override
    public Optional<ReplenishmentRule> findActiveRule(String skuId, String dcId) {
        return ruleRepo.findFirstBySkuIdAndDcIdAndActiveTrue(skuId, dcId)
                .map(ReplenishmentRuleJpaEntity::toDomain);
    }

    @Override
    public void savePurchaseOrder(PurchaseOrder po) {
        poRepo.save(PurchaseOrderJpaEntity.from(po));
    }

    @Override
    public void saveLineItem(PoLineItem item) {
        lineRepo.save(PoLineItemJpaEntity.from(item));
    }

    @Override
    @Transactional
    public int updateStatus(UUID poId,
            WorkflowStatus newStatus,
            int currentVersion,
            String approvedBy,
            Instant approvedAt,
            String rejectedBy,
            Instant rejectedAt,
            String rejectionReason) {
        return poRepo.updateStatus(poId, newStatus.name(), currentVersion,
                approvedBy, approvedAt, rejectedBy, rejectedAt, rejectionReason);
    }

    @Override
    public Optional<PurchaseOrder> findById(UUID poId) {
        return poRepo.findById(poId).map(PurchaseOrderJpaEntity::toDomain);
    }

    @Override
    public List<PurchaseOrder> findOrders(String status, String dcId, String skuId,
            int page, int size) {
        return poRepo.findAll(orderSpec(status, dcId, skuId),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(PurchaseOrderJpaEntity::toDomain)
                .toList();
    }

    @Override
    public long countOrders(String status, String dcId, String skuId) {
        return poRepo.count(orderSpec(status, dcId, skuId));
    }

    @Override
    public List<PoLineItem> findLineItemsByPoId(UUID poId) {
        return lineRepo.findByPoId(poId).stream()
                .map(PoLineItemJpaEntity::toDomain)
                .toList();
    }

    private static Specification<PurchaseOrderJpaEntity> orderSpec(
            String status, String dcId, String skuId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null && !status.isBlank())
                predicates.add(cb.equal(root.get("workflowStatus"), status));
            if (dcId != null && !dcId.isBlank())
                predicates.add(cb.equal(root.get("dcId"), dcId));
            if (skuId != null && !skuId.isBlank())
                predicates.add(cb.equal(root.get("skuId"), skuId));
            return predicates.isEmpty() ? null : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
