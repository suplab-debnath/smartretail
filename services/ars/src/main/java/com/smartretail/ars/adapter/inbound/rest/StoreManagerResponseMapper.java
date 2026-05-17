package com.smartretail.ars.adapter.inbound.rest;

import com.smartretail.ars.adapter.in.web.generated.model.AlertKpi;
import com.smartretail.ars.adapter.in.web.generated.model.StockAlertSummary;
import com.smartretail.ars.adapter.in.web.generated.model.StoreManagerDashboardResponse;
import com.smartretail.ars.domain.model.StoreManagerDashboard;

import java.time.ZoneOffset;
import java.util.List;

final class StoreManagerResponseMapper {

    private StoreManagerResponseMapper() {}

    static StoreManagerDashboardResponse toResponse(StoreManagerDashboard d) {
        AlertKpi kpi = new AlertKpi(
                d.alertKpi().criticalCount(),
                d.alertKpi().highCount(),
                d.alertKpi().mediumCount(),
                d.alertKpi().totalActive()
        );

        List<StockAlertSummary> alerts = d.alerts().stream()
                .map(a -> new StockAlertSummary(
                        a.alertId(),
                        a.skuId(),
                        a.dcId(),
                        StockAlertSummary.AlertTypeEnum.valueOf(a.alertType()),
                        StockAlertSummary.SeverityEnum.valueOf(a.severity()),
                        a.onHand(),
                        a.reorderPoint(),
                        a.raisedAt().atOffset(ZoneOffset.UTC)
                ))
                .toList();

        return new StoreManagerDashboardResponse(
                d.dcId(),
                kpi,
                d.totalOnHandUnits(),
                d.pendingReplenishmentCount(),
                d.forecastCoveragePct().doubleValue(),
                alerts,
                d.alertsPage(),
                d.alertsTotalPages(),
                d.dataFreshness().atOffset(ZoneOffset.UTC)
        );
    }
}
