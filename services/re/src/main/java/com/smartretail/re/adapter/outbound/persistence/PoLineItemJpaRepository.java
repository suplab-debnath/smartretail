package com.smartretail.re.adapter.outbound.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface PoLineItemJpaRepository extends JpaRepository<PoLineItemJpaEntity, UUID> {

    List<PoLineItemJpaEntity> findByPoId(UUID poId);
}
