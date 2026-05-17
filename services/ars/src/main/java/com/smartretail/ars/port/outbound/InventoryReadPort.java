package com.smartretail.ars.port.outbound;

import com.smartretail.ars.domain.model.ExecutiveDashboard.StockoutDataPoint;
import com.smartretail.ars.domain.model.StoreManagerDashboard.AlertKpi;
import com.smartretail.ars.domain.model.StoreManagerDashboard.AlertSummary;

import java.util.List;

public interface InventoryReadPort {
    /** Count of CRITICAL alerts within the last {@code days} days (Executive dashboard). */
    int countCriticalAlerts(int days);

    /** Daily CRITICAL alert history for the last {@code days} days (Executive dashboard). */
    List<StockoutDataPoint> findDailyCriticalAlertHistory(int days);

    /** Count of all ACTIVE stock alerts — used by SC Planner dashboard badge. */
    int countActiveAlerts();

    // ── Store Manager methods (scoped to a single DC) ──────────────────────────

    /** Severity breakdown of ACTIVE alerts for {@code dcId}. */
    AlertKpi countActiveAlertsByDc(String dcId);

    /** Total count of ACTIVE alerts for {@code dcId} (for pagination). */
    int countActiveAlertsByDcTotal(String dcId);

    /** Paginated list of ACTIVE alerts for {@code dcId}, sorted CRITICAL → HIGH → MEDIUM. */
    List<AlertSummary> findActiveAlertsByDc(String dcId, int page, int size);

    /** Sum of on_hand units across all inventory positions for {@code dcId}. */
    long sumOnHandByDc(String dcId);

    /** Count of distinct SKUs with at least one inventory position in {@code dcId}. */
    int countDistinctSkusByDc(String dcId);
}
