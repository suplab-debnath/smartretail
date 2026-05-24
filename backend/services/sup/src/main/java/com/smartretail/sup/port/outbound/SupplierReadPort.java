package com.smartretail.sup.port.outbound;

import java.util.List;
import java.util.UUID;

public interface SupplierReadPort {

    List<SupplierRow> findAllSuppliers();

    record SupplierRow(UUID supplierId, String supplierName) {}
}
