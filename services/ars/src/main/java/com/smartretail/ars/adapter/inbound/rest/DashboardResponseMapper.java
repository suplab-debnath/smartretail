package com.smartretail.ars.adapter.inbound.rest;

import com.smartretail.ars.adapter.in.web.generated.model.CycleTimeDataPoint;
import com.smartretail.ars.adapter.in.web.generated.model.DirectionTrend;
import com.smartretail.ars.adapter.in.web.generated.model.ExecutiveDashboardResponse;
import com.smartretail.ars.adapter.in.web.generated.model.ExecutiveKpis;
import com.smartretail.ars.adapter.in.web.generated.model.ForecastAccuracyKpi;
import com.smartretail.ars.adapter.in.web.generated.model.MapeDataPoint;
import com.smartretail.ars.adapter.in.web.generated.model.OnTimeDeliveryKpi;
import com.smartretail.ars.adapter.in.web.generated.model.ReplenishmentCycleTimeKpi;
import com.smartretail.ars.adapter.in.web.generated.model.ScPlannerDashboardResponse;
import com.smartretail.ars.adapter.in.web.generated.model.ScPlannerForecastAccuracy;
import com.smartretail.ars.adapter.in.web.generated.model.ScPlannerSupplierEntry;
import com.smartretail.ars.adapter.in.web.generated.model.StockoutAlertDataPoint;
import com.smartretail.ars.adapter.in.web.generated.model.StockoutFrequencyKpi;
import com.smartretail.ars.adapter.in.web.generated.model.SupplierPerformanceDashboardResponse;
import com.smartretail.ars.adapter.in.web.generated.model.SupplierPerformanceEntry;
import com.smartretail.ars.adapter.in.web.generated.model.Trend;
import com.smartretail.ars.domain.model.ExecutiveDashboard;
import com.smartretail.ars.domain.model.ScPlannerDashboard;
import com.smartretail.ars.domain.model.SupplierPerformanceDashboard;
import org.mapstruct.Mapper;

import java.time.ZoneOffset;
import java.util.List;

@Mapper(componentModel = "spring")
public interface DashboardResponseMapper {

    default ExecutiveDashboardResponse toExecutiveResponse(ExecutiveDashboard d) {
        ForecastAccuracyKpi forecastKpi = new ForecastAccuracyKpi(
                d.forecastAccuracy().latestMape().doubleValue(),
                Trend.valueOf(d.forecastAccuracy().trend().name()),
                d.forecastAccuracy().history().stream()
                        .map(p -> new MapeDataPoint(p.runDate(), p.mape().doubleValue()))
                        .toList()
        );
        StockoutFrequencyKpi stockoutKpi = new StockoutFrequencyKpi(
                d.stockoutFrequency().last30Days(),
                DirectionTrend.valueOf(d.stockoutFrequency().trend().name()),
                d.stockoutFrequency().history().stream()
                        .map(p -> new StockoutAlertDataPoint(p.alertDate(), p.criticalCount()))
                        .toList()
        );
        ReplenishmentCycleTimeKpi cycleKpi = new ReplenishmentCycleTimeKpi(
                d.replenishmentCycleTime().averageDays().doubleValue(),
                Trend.valueOf(d.replenishmentCycleTime().trend().name()),
                d.replenishmentCycleTime().history().stream()
                        .map(p -> new CycleTimeDataPoint(p.weekStart(), p.averageDays().doubleValue(), p.poCount()))
                        .toList()
        );
        OnTimeDeliveryKpi otdKpi = new OnTimeDeliveryKpi(
                d.onTimeDelivery().rate().doubleValue(),
                Trend.valueOf(d.onTimeDelivery().trend().name())
        );
        List<SupplierPerformanceEntry> supplierKpis = d.supplierPerformance().stream()
                .map(s -> new SupplierPerformanceEntry(
                        s.supplierId(), s.supplierName(),
                        s.otdRate().doubleValue(), s.fillRate().doubleValue(),
                        s.earlyCount(), s.onTimeCount(), s.lateCount(), s.openExceptions()))
                .toList();
        ExecutiveKpis kpis = new ExecutiveKpis(forecastKpi, stockoutKpi, cycleKpi, otdKpi, supplierKpis);
        return new ExecutiveDashboardResponse(kpis, d.dataFreshness().atOffset(ZoneOffset.UTC));
    }

    default ScPlannerDashboardResponse toScPlannerResponse(ScPlannerDashboard d) {
        ScPlannerForecastAccuracy acc = new ScPlannerForecastAccuracy(
                d.forecastAccuracy().latestMape().doubleValue(),
                d.forecastAccuracy().mapeThreshold().doubleValue(),
                d.forecastAccuracy().lastRunAt().atOffset(ZoneOffset.UTC),
                ScPlannerForecastAccuracy.StatusEnum.valueOf(d.forecastAccuracy().status().name())
        );
        return new ScPlannerDashboardResponse(
                d.pendingApprovalCount(),
                d.activeAlertCount(),
                acc,
                d.dataFreshness().atOffset(ZoneOffset.UTC)
        );
    }

    default SupplierPerformanceDashboardResponse toSupplierPerfResponse(SupplierPerformanceDashboard d) {
        List<ScPlannerSupplierEntry> entries = d.suppliers().stream()
                .map(s -> new ScPlannerSupplierEntry(
                        s.supplierId(),
                        s.supplierName(),
                        s.onTimeDeliveryRate().doubleValue(),
                        s.poAcknowledgementSlaCompliance().doubleValue(),
                        s.openExceptions(),
                        s.avgLeadTimeVarianceDays().doubleValue(),
                        s.totalPoCount(),
                        s.totalPoValue().doubleValue()
                ))
                .toList();
        return new SupplierPerformanceDashboardResponse(entries, d.dataFreshness().atOffset(ZoneOffset.UTC));
    }
}
