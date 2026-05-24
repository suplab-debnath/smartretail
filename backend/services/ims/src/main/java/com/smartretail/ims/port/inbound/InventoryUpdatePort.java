package com.smartretail.ims.port.inbound;

import com.smartretail.ims.domain.model.SalesTransactionEventDto;

/** Inbound port: decrement inventory and raise alerts on sales events. */
public interface InventoryUpdatePort {
    void processSalesEvent(SalesTransactionEventDto event);
}
