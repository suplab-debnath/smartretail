package com.smartretail.sup.domain.model;

import java.util.List;
import java.util.UUID;

public record SupplierRecordList(List<SupplierEntry> suppliers) {

    public record SupplierEntry(UUID supplierId, String supplierName) {}
}
