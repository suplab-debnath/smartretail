package com.smartretail.sis.port.outbound;

import com.smartretail.sis.domain.model.SalesTransaction;

/** Outbound port: publishes a SalesTransactionEvent to the event bus. */
public interface EventPublisherPort {
    void publishSalesTransactionEvent(SalesTransaction transaction);
}
