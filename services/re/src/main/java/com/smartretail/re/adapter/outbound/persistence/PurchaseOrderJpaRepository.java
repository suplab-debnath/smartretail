package com.smartretail.re.adapter.outbound.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

interface PurchaseOrderJpaRepository
        extends JpaRepository<PurchaseOrderJpaEntity, UUID>,
        JpaSpecificationExecutor<PurchaseOrderJpaEntity> {

    @Modifying
    @Query(value = """
            UPDATE replenishment.purchase_orders
            SET workflow_status  = :newStatus,
                version          = version + 1,
                approved_by      = :approvedBy,
                approved_at      = :approvedAt,
                rejected_by      = :rejectedBy,
                rejected_at      = :rejectedAt,
                rejection_reason = :rejectionReason,
                updated_at       = NOW()
            WHERE po_id = :poId
              AND version = :currentVersion
            """, nativeQuery = true)
    int updateStatus(@Param("poId") UUID poId,
            @Param("newStatus") String newStatus,
            @Param("currentVersion") int currentVersion,
            @Param("approvedBy") String approvedBy,
            @Param("approvedAt") Instant approvedAt,
            @Param("rejectedBy") String rejectedBy,
            @Param("rejectedAt") Instant rejectedAt,
            @Param("rejectionReason") String rejectionReason);
}
