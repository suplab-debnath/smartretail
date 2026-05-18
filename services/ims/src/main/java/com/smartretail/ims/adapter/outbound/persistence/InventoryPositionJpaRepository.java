package com.smartretail.ims.adapter.outbound.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface InventoryPositionJpaRepository
        extends JpaRepository<InventoryPositionJpaEntity, UUID>,
        JpaSpecificationExecutor<InventoryPositionJpaEntity> {

    Optional<InventoryPositionJpaEntity> findBySkuIdAndDcId(String skuId, String dcId);

    @Modifying
    @Query(value = """
            UPDATE inventory.inventory_positions
            SET on_hand = on_hand - :quantity,
                last_updated_at = NOW(),
                version = version + 1
            WHERE position_id = :positionId
              AND version = :version
              AND on_hand >= :quantity
            """, nativeQuery = true)
    int decrementOnHand(@Param("positionId") UUID positionId,
            @Param("quantity") int quantity,
            @Param("version") int version);
}
