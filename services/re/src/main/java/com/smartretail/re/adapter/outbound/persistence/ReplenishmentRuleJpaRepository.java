package com.smartretail.re.adapter.outbound.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface ReplenishmentRuleJpaRepository extends JpaRepository<ReplenishmentRuleJpaEntity, UUID> {

    Optional<ReplenishmentRuleJpaEntity> findFirstBySkuIdAndDcIdAndActiveTrue(String skuId, String dcId);
}
