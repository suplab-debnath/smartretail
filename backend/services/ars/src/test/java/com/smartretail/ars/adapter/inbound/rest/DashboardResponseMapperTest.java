package com.smartretail.ars.adapter.inbound.rest;

import com.smartretail.ars.adapter.in.web.generated.model.ExecutiveDashboardResponse;
import com.smartretail.ars.adapter.in.web.generated.model.ScPlannerDashboardResponse;
import com.smartretail.ars.adapter.in.web.generated.model.SupplierOrdersDashboardResponse;
import com.smartretail.ars.adapter.in.web.generated.model.SupplierPerformanceDashboardResponse;
import com.smartretail.ars.domain.model.ExecutiveDashboard;
import com.smartretail.ars.domain.model.ScPlannerDashboard;
import com.smartretail.ars.domain.model.SupplierOrdersDashboard;
import com.smartretail.ars.domain.model.SupplierPerformanceDashboard;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardResponseMapperTest {

    private final DashboardResponseMapper mapper = Mappers.getMapper(DashboardResponseMapper.class);

    // ── toExecutiveResponse ───────────────────────────────────────────────────

    @Test
    void shouldMapExecutiveDashboardWithImprovingTrend() {
        ExecutiveDashboard d = execDashboard(
                ExecutiveDashboard.Trend.IMPROVING,
                ExecutiveDashboard.DirectionTrend.DECREASING,
                ExecutiveDashboard.Trend.IMPROVING);

        ExecutiveDashboardResponse response = mapper.toExecutiveResponse(d);

        assertThat(response.getKpis().getForecastAccuracy().getTrend().getValue()).isEqualTo("IMPROVING");
        assertThat(response.getKpis().getStockoutFrequency().getTrend().getValue()).isEqualTo("DECREASING");
        assertThat(response.getKpis().getReplenishmentCycleTime().getTrend().getValue()).isEqualTo("IMPROVING");
    }

    @Test
    void shouldMapExecutiveDashboardWithDegradingTrend() {
        ExecutiveDashboard d = execDashboard(
                ExecutiveDashboard.Trend.DEGRADING,
                ExecutiveDashboard.DirectionTrend.INCREASING,
                ExecutiveDashboard.Trend.DEGRADING);

        ExecutiveDashboardResponse response = mapper.toExecutiveResponse(d);

        assertThat(response.getKpis().getForecastAccuracy().getTrend().getValue()).isEqualTo("DEGRADING");
        assertThat(response.getKpis().getStockoutFrequency().getTrend().getValue()).isEqualTo("INCREASING");
        assertThat(response.getKpis().getReplenishmentCycleTime().getTrend().getValue()).isEqualTo("DEGRADING");
    }

    @Test
    void shouldMapExecutiveDashboardWithHistoryDataPoints() {
        var mapePoint = new ExecutiveDashboard.MapeDataPoint(LocalDate.of(2026, 5, 1), BigDecimal.valueOf(12.0));
        var stockoutPoint = new ExecutiveDashboard.StockoutDataPoint(LocalDate.of(2026, 5, 1), 3);
        var cyclePoint = new ExecutiveDashboard.CycleTimeDataPoint(LocalDate.of(2026, 5, 1), BigDecimal.valueOf(3.5), 10);
        var supplier = new ExecutiveDashboard.SupplierPerformanceEntry(
                UUID.randomUUID(), "Acme", BigDecimal.valueOf(0.9), BigDecimal.valueOf(0.85),
                2, 10, 1, 0);

        var fa = new ExecutiveDashboard.ForecastAccuracy(BigDecimal.valueOf(12.0), ExecutiveDashboard.Trend.STABLE, List.of(mapePoint));
        var sf = new ExecutiveDashboard.StockoutFrequency(5, ExecutiveDashboard.DirectionTrend.STABLE, List.of(stockoutPoint));
        var ct = new ExecutiveDashboard.ReplenishmentCycleTime(BigDecimal.valueOf(3.5), ExecutiveDashboard.Trend.STABLE, List.of(cyclePoint));
        var otd = new ExecutiveDashboard.OnTimeDelivery(BigDecimal.valueOf(0.92), ExecutiveDashboard.Trend.STABLE);
        ExecutiveDashboard d = new ExecutiveDashboard(fa, sf, ct, otd, List.of(supplier), Instant.now());

        ExecutiveDashboardResponse response = mapper.toExecutiveResponse(d);

        assertThat(response.getKpis().getForecastAccuracy().getHistory()).hasSize(1);
        assertThat(response.getKpis().getStockoutFrequency().getHistory()).hasSize(1);
        assertThat(response.getKpis().getReplenishmentCycleTime().getHistory()).hasSize(1);
        assertThat(response.getKpis().getSupplierPerformance()).hasSize(1);
        assertThat(response.getKpis().getSupplierPerformance().get(0).getSupplierName()).isEqualTo("Acme");
    }

    // ── toScPlannerResponse ───────────────────────────────────────────────────

    @Test
    void shouldMapScPlannerDashboardWithAboveThresholdStatus() {
        var acc = new ScPlannerDashboard.ForecastAccuracy(
                BigDecimal.valueOf(20.0), BigDecimal.valueOf(15.0),
                Instant.now(), ScPlannerDashboard.MapeStatus.ABOVE_THRESHOLD);
        ScPlannerDashboard d = new ScPlannerDashboard(5, 2, acc, Instant.now());

        ScPlannerDashboardResponse response = mapper.toScPlannerResponse(d);

        assertThat(response.getForecastAccuracy().getStatus().getValue()).isEqualTo("ABOVE_THRESHOLD");
        assertThat(response.getPendingApprovalCount()).isEqualTo(5);
        assertThat(response.getActiveAlertCount()).isEqualTo(2);
    }

    @Test
    void shouldMapScPlannerDashboardWithinThresholdStatus() {
        var acc = new ScPlannerDashboard.ForecastAccuracy(
                BigDecimal.valueOf(10.0), BigDecimal.valueOf(15.0),
                Instant.now(), ScPlannerDashboard.MapeStatus.WITHIN_THRESHOLD);
        ScPlannerDashboard d = new ScPlannerDashboard(0, 0, acc, Instant.now());

        ScPlannerDashboardResponse response = mapper.toScPlannerResponse(d);

        assertThat(response.getForecastAccuracy().getStatus().getValue()).isEqualTo("WITHIN_THRESHOLD");
    }

    // ── toSupplierOrdersResponse ──────────────────────────────────────────────

    @Test
    void shouldMapSupplierOrderWithAllTimestampsPresent() {
        var entry = new SupplierOrdersDashboard.SupplierOrderEntry(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Acme Beverages", "SKU-BEV-001", "DC-LONDON", 500, "DISPATCHED",
                Instant.parse("2026-05-01T10:00:00Z"),
                Instant.parse("2026-05-03T08:00:00Z"),
                LocalDate.of(2026, 5, 10),
                Instant.parse("2026-05-03T09:00:00Z"));
        SupplierOrdersDashboard d = new SupplierOrdersDashboard(List.of(entry), Instant.now());

        SupplierOrdersDashboardResponse response = mapper.toSupplierOrdersResponse(d);

        assertThat(response.getOrders()).hasSize(1);
        assertThat(response.getOrders().get(0).getConfirmedAt()).isNotNull();
        assertThat(response.getOrders().get(0).getDispatchedAt()).isNotNull();
        assertThat(response.getOrders().get(0).getEta()).isEqualTo(LocalDate.of(2026, 5, 10));
        assertThat(response.getOrders().get(0).getLastUpdateAt()).isNotNull();
    }

    @Test
    void shouldMapSupplierOrderWithAllTimestampsNull() {
        var entry = new SupplierOrdersDashboard.SupplierOrderEntry(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Acme Beverages", "SKU-BEV-001", "DC-LONDON", 500, "PENDING",
                null, null, null, null);
        SupplierOrdersDashboard d = new SupplierOrdersDashboard(List.of(entry), Instant.now());

        SupplierOrdersDashboardResponse response = mapper.toSupplierOrdersResponse(d);

        assertThat(response.getOrders()).hasSize(1);
        assertThat(response.getOrders().get(0).getConfirmedAt()).isNull();
        assertThat(response.getOrders().get(0).getDispatchedAt()).isNull();
        assertThat(response.getOrders().get(0).getEta()).isNull();
        assertThat(response.getOrders().get(0).getLastUpdateAt()).isNull();
    }

    @Test
    void shouldMapEmptySupplierOrdersList() {
        SupplierOrdersDashboard d = new SupplierOrdersDashboard(List.of(), Instant.now());

        SupplierOrdersDashboardResponse response = mapper.toSupplierOrdersResponse(d);

        assertThat(response.getOrders()).isEmpty();
    }

    // ── toSupplierPerfResponse ────────────────────────────────────────────────

    @Test
    void shouldMapSupplierPerformanceEntries() {
        var entry = new SupplierPerformanceDashboard.SupplierEntry(
                UUID.randomUUID(), "Acme Beverages",
                BigDecimal.valueOf(0.92), BigDecimal.valueOf(0.88),
                2, BigDecimal.valueOf(1.5), 30, BigDecimal.valueOf(45000));
        SupplierPerformanceDashboard d = new SupplierPerformanceDashboard(List.of(entry), Instant.now());

        SupplierPerformanceDashboardResponse response = mapper.toSupplierPerfResponse(d);

        assertThat(response.getSuppliers()).hasSize(1);
        assertThat(response.getSuppliers().get(0).getSupplierName()).isEqualTo("Acme Beverages");
        assertThat(response.getSuppliers().get(0).getOnTimeDeliveryRate()).isEqualTo(0.92);
        assertThat(response.getSuppliers().get(0).getTotalPoCount()).isEqualTo(30);
    }

    @Test
    void shouldMapEmptySupplierPerformanceList() {
        SupplierPerformanceDashboard d = new SupplierPerformanceDashboard(List.of(), Instant.now());

        SupplierPerformanceDashboardResponse response = mapper.toSupplierPerfResponse(d);

        assertThat(response.getSuppliers()).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ExecutiveDashboard execDashboard(
            ExecutiveDashboard.Trend forecastTrend,
            ExecutiveDashboard.DirectionTrend stockoutTrend,
            ExecutiveDashboard.Trend cycleTrend) {
        var fa = new ExecutiveDashboard.ForecastAccuracy(BigDecimal.valueOf(12.5), forecastTrend, List.of());
        var sf = new ExecutiveDashboard.StockoutFrequency(5, stockoutTrend, List.of());
        var ct = new ExecutiveDashboard.ReplenishmentCycleTime(BigDecimal.valueOf(3.5), cycleTrend, List.of());
        var otd = new ExecutiveDashboard.OnTimeDelivery(BigDecimal.valueOf(0.92), ExecutiveDashboard.Trend.STABLE);
        return new ExecutiveDashboard(fa, sf, ct, otd, List.of(), Instant.now());
    }
}
