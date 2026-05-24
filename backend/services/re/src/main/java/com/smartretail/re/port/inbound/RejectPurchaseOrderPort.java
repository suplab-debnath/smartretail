package com.smartretail.re.port.inbound;

import com.smartretail.re.domain.model.PurchaseOrder;

import java.util.UUID;

public interface RejectPurchaseOrderPort {

    PurchaseOrder reject(UUID poId, int currentVersion, String rejectedBy, String rejectionReason);
}
