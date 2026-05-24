package com.smartretail.re.port.inbound;

import com.smartretail.re.domain.model.PurchaseOrder;

import java.util.UUID;

public interface ApprovePurchaseOrderPort {

    PurchaseOrder approve(UUID poId, int currentVersion, String approvedBy);
}
