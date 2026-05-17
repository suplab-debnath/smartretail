package com.smartretail.ars.port.inbound;

import com.smartretail.ars.domain.model.StoreManagerDashboard;

public interface StoreManagerDashboardPort {
    StoreManagerDashboard assemble(String dcId, int page, int size);
}
