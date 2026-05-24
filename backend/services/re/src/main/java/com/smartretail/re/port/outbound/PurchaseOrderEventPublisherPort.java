package com.smartretail.re.port.outbound;

import com.smartretail.re.domain.model.PurchaseOrder;

public interface PurchaseOrderEventPublisherPort {

    void publishPurchaseOrderEvent(PurchaseOrder po);
}
