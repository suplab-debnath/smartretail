package com.smartretail.sis.port.outbound;

import com.smartretail.sis.domain.model.SalesTransaction;

/** Outbound port: persists a sales transaction to the relational store. */
public interface EventStorePort {
    void save(SalesTransaction transaction, String rawS3Reference);
}
