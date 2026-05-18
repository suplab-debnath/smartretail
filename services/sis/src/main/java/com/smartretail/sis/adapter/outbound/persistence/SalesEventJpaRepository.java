package com.smartretail.sis.adapter.outbound.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SalesEventJpaRepository extends JpaRepository<SalesTransactionJpaEntity, SalesEventId> {
}
