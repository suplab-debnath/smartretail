package com.smartretail.re.port.outbound;

import com.smartretail.re.domain.model.PoLineItem;
import com.smartretail.re.domain.model.PurchaseOrder;
import com.smartretail.re.domain.model.ReplenishmentRule;
import com.smartretail.re.domain.model.WorkflowStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReplenishmentRepositoryPort {

    // Rule lookup (replenishment.replenishment_rules — no cross-schema joins)
    Optional<ReplenishmentRule> findActiveRule(String skuId, String dcId);

    // PO writes
    void savePurchaseOrder(PurchaseOrder po);
    void saveLineItem(PoLineItem item);

    /**
     * Optimistic-lock status transition.
     * Returns the number of rows updated (0 = version conflict, 1 = success).
     * The WHERE clause enforces: po_id = :poId AND version = :currentVersion.
     */
    int updateStatus(UUID poId,
                     WorkflowStatus newStatus,
                     int currentVersion,
                     String approvedBy,
                     Instant approvedAt,
                     String rejectedBy,
                     Instant rejectedAt,
                     String rejectionReason);

    // PO reads
    Optional<PurchaseOrder> findById(UUID poId);
    List<PurchaseOrder> findOrders(String status, String dcId, String skuId, int page, int size);
    long countOrders(String status, String dcId, String skuId);
    List<PoLineItem> findLineItemsByPoId(UUID poId);
}
