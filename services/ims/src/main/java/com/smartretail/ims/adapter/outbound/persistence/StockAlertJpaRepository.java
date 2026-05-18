package com.smartretail.ims.adapter.outbound.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

interface StockAlertJpaRepository extends JpaRepository<StockAlertJpaEntity, UUID> {

    @Query(value = """
            SELECT sa FROM StockAlertJpaEntity sa JOIN FETCH sa.position p
            WHERE (:dcId IS NULL OR p.dcId = :dcId)
              AND (:severity IS NULL OR sa.severity = :severity)
              AND (:status IS NULL OR sa.status = :status)
            ORDER BY sa.raisedAt DESC
            """, countQuery = """
            SELECT COUNT(sa) FROM StockAlertJpaEntity sa JOIN sa.position p
            WHERE (:dcId IS NULL OR p.dcId = :dcId)
              AND (:severity IS NULL OR sa.severity = :severity)
              AND (:status IS NULL OR sa.status = :status)
            """)
    Page<StockAlertJpaEntity> findFiltered(@Param("dcId") String dcId,
            @Param("severity") String severity,
            @Param("status") String status,
            Pageable pageable);

    @Query("""
            SELECT COUNT(sa) FROM StockAlertJpaEntity sa JOIN sa.position p
            WHERE (:dcId IS NULL OR p.dcId = :dcId)
              AND (:severity IS NULL OR sa.severity = :severity)
              AND (:status IS NULL OR sa.status = :status)
            """)
    long countFiltered(@Param("dcId") String dcId,
            @Param("severity") String severity,
            @Param("status") String status);
}
