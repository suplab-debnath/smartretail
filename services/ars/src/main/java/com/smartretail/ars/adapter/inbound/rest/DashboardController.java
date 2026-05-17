package com.smartretail.ars.adapter.inbound.rest;

import com.smartretail.ars.adapter.in.web.generated.api.DashboardApi;
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
import com.smartretail.ars.adapter.in.web.generated.model.StockoutAlertDataPoint;
import com.smartretail.ars.adapter.in.web.generated.model.StockoutFrequencyKpi;
import com.smartretail.ars.adapter.in.web.generated.model.StoreManagerDashboardResponse;
import com.smartretail.ars.adapter.in.web.generated.model.SupplierPerformanceDashboardResponse;
import com.smartretail.ars.adapter.in.web.generated.model.SupplierPerformanceEntry;
import com.smartretail.ars.adapter.in.web.generated.model.Trend;
import com.smartretail.ars.adapter.in.web.generated.model.ScPlannerSupplierEntry;
import com.smartretail.ars.domain.model.ExecutiveDashboard;
import com.smartretail.ars.domain.model.ScPlannerDashboard;
import com.smartretail.ars.domain.model.SupplierPerformanceDashboard;
import com.smartretail.ars.port.inbound.ExecutiveDashboardPort;
import com.smartretail.ars.port.inbound.ScPlannerDashboardPort;
import com.smartretail.ars.port.inbound.StoreManagerDashboardPort;
import com.smartretail.ars.port.inbound.SupplierPerformancePort;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

@RestController
@Tag(name = "dashboard", description = "Persona-specific aggregated dashboard payloads")
public class DashboardController implements DashboardApi {

    private static final Set<String> EXECUTIVE_ROLES    = Set.of("EXECUTIVE", "SC_PLANNER", "ADMIN");
    private static final Set<String> PLANNER_ROLES      = Set.of("SC_PLANNER", "ADMIN");
    private static final Set<String> STORE_MANAGER_ROLES = Set.of("STORE_MANAGER", "ADMIN");

    private final ExecutiveDashboardPort executiveDashboardPort;
    private final ScPlannerDashboardPort scPlannerDashboardPort;
    private final SupplierPerformancePort supplierPerformancePort;
    private final StoreManagerDashboardPort storeManagerDashboardPort;

    @Autowired
    private HttpServletRequest httpRequest;

    public DashboardController(
            ExecutiveDashboardPort executiveDashboardPort,
            ScPlannerDashboardPort scPlannerDashboardPort,
            SupplierPerformancePort supplierPerformancePort,
            StoreManagerDashboardPort storeManagerDashboardPort) {
        this.executiveDashboardPort    = executiveDashboardPort;
        this.scPlannerDashboardPort    = scPlannerDashboardPort;
        this.supplierPerformancePort   = supplierPerformancePort;
        this.storeManagerDashboardPort = storeManagerDashboardPort;
    }

    // ── Executive Dashboard (Flow 8) ─────────────────────────────────────────

    @Override
    public ResponseEntity<ExecutiveDashboardResponse> getExecutiveDashboard() {
        if (!hasAnyRole(EXECUTIVE_ROLES)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(toExecutiveResponse(executiveDashboardPort.assemble()));
    }

    // ── Store Manager Dashboard (Flow 4) ─────────────────────────────────────

    @Override
    public ResponseEntity<StoreManagerDashboardResponse> getStoreManagerDashboard(
            String dcId, Integer page, Integer size) {
        if (!hasAnyRole(STORE_MANAGER_ROLES)) return ResponseEntity.status(403).build();
        int p = page != null ? page : 0;
        int s = size != null ? size : 10;
        return ResponseEntity.ok(
                StoreManagerResponseMapper.toResponse(storeManagerDashboardPort.assemble(dcId, p, s))
        );
    }

    // ── SC Planner Dashboard (Flow 9) ────────────────────────────────────────

    @Override
    public ResponseEntity<ScPlannerDashboardResponse> getScPlannerDashboard() {
        if (!hasAnyRole(PLANNER_ROLES)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(toScPlannerResponse(scPlannerDashboardPort.assemble()));
    }

    // ── Supplier Performance Scorecard (Flow 9) ──────────────────────────────

    @Override
    public ResponseEntity<SupplierPerformanceDashboardResponse> getSupplierPerformanceDashboard() {
        if (!hasAnyRole(PLANNER_ROLES)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(toSupplierPerfResponse(supplierPerformancePort.assemble()));
    }

    // ── Role extraction ───────────────────────────────────────────────────────

    private boolean hasAnyRole(Set<String> allowed) {
        return extractRoles().stream().anyMatch(allowed::contains);
    }

    /**
     * Local mode: role from X-Dev-Role header (defaults to EXECUTIVE).
     * AWS mode:   roles from cognito:groups JWT claim.
     */
    private Set<String> extractRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            List<String> groups = jwt.getClaimAsStringList("cognito:groups");
            return groups != null ? Set.copyOf(groups) : Set.of();
        }
        String header = httpRequest.getHeader("X-Dev-Role");
        return Set.of(header != null ? header : "EXECUTIVE");
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private ExecutiveDashboardResponse toExecutiveResponse(ExecutiveDashboard d) {
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

    private ScPlannerDashboardResponse toScPlannerResponse(ScPlannerDashboard d) {
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

    private SupplierPerformanceDashboardResponse toSupplierPerfResponse(SupplierPerformanceDashboard d) {
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
