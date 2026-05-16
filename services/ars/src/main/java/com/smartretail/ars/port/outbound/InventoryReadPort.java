package com.smartretail.ars.port.outbound;

import com.smartretail.ars.domain.model.ExecutiveDashboard.StockoutDataPoint;

import java.util.List;

public interface InventoryReadPort {
    /** Count of CRITICAL alerts within the last {@code days} days (Executive dashboard). */
    int countCriticalAlerts(int days);

    /** Daily CRITICAL alert history for the last {@code days} days (Executive dashboard). */
    List<StockoutDataPoint> findDailyCriticalAlertHistory(int days);

    /** Count of all ACTIVE stock alerts — used by SC Planner dashboard badge. */
    int countActiveAlerts();
}
