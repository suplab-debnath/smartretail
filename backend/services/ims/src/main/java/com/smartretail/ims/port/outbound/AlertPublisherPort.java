package com.smartretail.ims.port.outbound;

import com.smartretail.ims.domain.model.InventoryPosition;
import com.smartretail.ims.domain.model.StockAlert;

/** Outbound port: publishes an InventoryAlertEvent to the event bus. */
public interface AlertPublisherPort {
    void publishInventoryAlertEvent(StockAlert alert, InventoryPosition position);
}
