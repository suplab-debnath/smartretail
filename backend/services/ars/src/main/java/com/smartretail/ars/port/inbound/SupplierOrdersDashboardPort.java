package com.smartretail.ars.port.inbound;

import com.smartretail.ars.domain.model.SupplierOrdersDashboard;

public interface SupplierOrdersDashboardPort {
    SupplierOrdersDashboard assemble(String status);
}
