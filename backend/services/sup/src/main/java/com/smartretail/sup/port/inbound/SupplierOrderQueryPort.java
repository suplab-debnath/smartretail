package com.smartretail.sup.port.inbound;

import com.smartretail.sup.domain.model.SupplierOrderList;

/**
 * Inbound port: list supplier POs with shipment progress.
 */
public interface SupplierOrderQueryPort {
    SupplierOrderList getSupplierOrders(String shipmentStatus);
}
