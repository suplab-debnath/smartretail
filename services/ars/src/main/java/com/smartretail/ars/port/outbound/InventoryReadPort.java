package com.smartretail.ars.port.outbound;

import com.smartretail.ars.domain.model.ExecutiveDashboard.StockoutDataPoint;

import java.util.List;

public interface InventoryReadPort {
    int countCriticalAlerts(int days);
    List<StockoutDataPoint> findDailyCriticalAlertHistory(int days);
}
