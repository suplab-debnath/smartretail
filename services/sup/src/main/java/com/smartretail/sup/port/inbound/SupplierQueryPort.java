package com.smartretail.sup.port.inbound;

import com.smartretail.sup.domain.model.SupplierRecordList;

public interface SupplierQueryPort {
    SupplierRecordList getSuppliers();
}
