package com.smartretail.sis.adapter.outbound.persistence;

import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.outbound.EventStorePort;
import org.springframework.stereotype.Repository;

@Repository
public class SalesEventRepository implements EventStorePort {

    private final SalesEventJpaRepository jpaRepository;

    public SalesEventRepository(SalesEventJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(SalesTransaction transaction, String rawS3Reference) {
        jpaRepository.save(SalesTransactionJpaEntity.from(transaction, rawS3Reference));
    }
}
