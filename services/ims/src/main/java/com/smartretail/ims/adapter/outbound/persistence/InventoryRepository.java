package com.smartretail.ims.adapter.outbound.persistence;

import com.smartretail.ims.domain.model.InventoryPosition;
import com.smartretail.ims.domain.model.StockAlert;
import com.smartretail.ims.port.outbound.InventoryRepositoryPort;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InventoryRepository implements InventoryRepositoryPort {

    private final InventoryPositionJpaRepository posRepo;
    private final StockAlertJpaRepository alertRepo;

    public InventoryRepository(InventoryPositionJpaRepository posRepo,
            StockAlertJpaRepository alertRepo) {
        this.posRepo = posRepo;
        this.alertRepo = alertRepo;
    }

    @Override
    public Optional<InventoryPosition> findBySkuAndDc(String skuId, String dcId) {
        return posRepo.findBySkuIdAndDcId(skuId, dcId).map(InventoryPositionJpaEntity::toDomain);
    }

    @Override
    public Optional<InventoryPosition> findById(UUID positionId) {
        return posRepo.findById(positionId).map(InventoryPositionJpaEntity::toDomain);
    }

    @Override
    @Transactional
    public int decrementOnHand(UUID positionId, int quantity, int currentVersion) {
        return posRepo.decrementOnHand(positionId, quantity, currentVersion);
    }

    @Override
    public void saveAlert(StockAlert alert) {
        InventoryPositionJpaEntity posRef = posRepo.getReferenceById(alert.getPositionId());
        alertRepo.save(StockAlertJpaEntity.from(alert, posRef));
    }

    @Override
    public List<InventoryPosition> findPositions(String dcId, String skuId, int page, int size) {
        Specification<InventoryPositionJpaEntity> spec = positionSpec(dcId, skuId);
        return posRepo.findAll(spec, PageRequest.of(page, size, Sort.by("dcId", "skuId")))
                .stream()
                .map(InventoryPositionJpaEntity::toDomain)
                .toList();
    }

    @Override
    public long countPositions(String dcId, String skuId) {
        return posRepo.count(positionSpec(dcId, skuId));
    }

    @Override
    public List<StockAlert> findAlerts(String dcId, String severity, String status,
            int page, int size) {
        return alertRepo.findFiltered(
                nullIfBlank(dcId), nullIfBlank(severity), nullIfBlank(status),
                PageRequest.of(page, size))
                .stream()
                .map(StockAlertJpaEntity::toDomain)
                .toList();
    }

    @Override
    public long countAlerts(String dcId, String severity, String status) {
        return alertRepo.countFiltered(nullIfBlank(dcId), nullIfBlank(severity), nullIfBlank(status));
    }

    private static Specification<InventoryPositionJpaEntity> positionSpec(String dcId, String skuId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (dcId != null && !dcId.isBlank())
                predicates.add(cb.equal(root.get("dcId"), dcId));
            if (skuId != null && !skuId.isBlank())
                predicates.add(cb.equal(root.get("skuId"), skuId));
            return predicates.isEmpty() ? null : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
