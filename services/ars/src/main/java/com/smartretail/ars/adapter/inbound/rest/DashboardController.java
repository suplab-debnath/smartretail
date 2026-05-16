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
import com.smartretail.ars.adapter.in.web.generated.model.StockoutAlertDataPoint;
import com.smartretail.ars.adapter.in.web.generated.model.StockoutFrequencyKpi;
import com.smartretail.ars.adapter.in.web.generated.model.SupplierPerformanceEntry;
import com.smartretail.ars.adapter.in.web.generated.model.Trend;
import com.smartretail.ars.domain.model.ExecutiveDashboard;
import com.smartretail.ars.port.inbound.ExecutiveDashboardPort;
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
@Tag(name = "dashboard", description = "Persona-specific dashboard payloads")
public class DashboardController implements DashboardApi {

    private static final Set<String> EXECUTIVE_ROLES = Set.of("EXECUTIVE", "SC_PLANNER", "ADMIN");
    private static final Set<String> PLANNER_ROLES   = Set.of("SC_PLANNER", "ADMIN");

    private final ExecutiveDashboardPort executiveDashboardPort;

    @Autowired
    private HttpServletRequest httpRequest;

    public DashboardController(ExecutiveDashboardPort executiveDashboardPort) {
        this.executiveDashboardPort = executiveDashboardPort;
    }

    @Override
    public ResponseEntity<ExecutiveDashboardResponse> getExecutiveDashboard() {
        if (!hasAnyRole(EXECUTIVE_ROLES)) {
            return ResponseEntity.status(403).build();
        }
        ExecutiveDashboard dashboard = executiveDashboardPort.assemble();
        return ResponseEntity.ok(toResponse(dashboard));
    }

    @Override
    public ResponseEntity<Object> getStoreManagerDashboard(String dcId) {
        return ResponseEntity.status(501).build();
    }

    @Override
    public ResponseEntity<Object> getScPlannerDashboard() {
        if (!hasAnyRole(PLANNER_ROLES)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.status(501).build();
    }

    @Override
    public ResponseEntity<Object> getSupplierPerformanceDashboard() {
        if (!hasAnyRole(PLANNER_ROLES)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.status(501).build();
    }

    private boolean hasAnyRole(Set<String> allowed) {
        return extractRoles().stream().anyMatch(allowed::contains);
    }

    /**
     * Local mode: role from X-Dev-Role header (defaults to EXECUTIVE).
     * AWS mode: role from cognito:groups JWT claim.
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

    private ExecutiveDashboardResponse toResponse(ExecutiveDashboard domain) {
        ForecastAccuracyKpi forecastKpi = new ForecastAccuracyKpi(
                domain.forecastAccuracy().latestMape().doubleValue(),
                Trend.valueOf(domain.forecastAccuracy().trend().name()),
                domain.forecastAccuracy().history().stream()
                        .map(p -> new MapeDataPoint(p.runDate(), p.mape().doubleValue()))
                        .toList()
        );

        List<StockoutAlertDataPoint> stockoutHistory = domain.stockoutFrequency().history().stream()
                .map(p -> new StockoutAlertDataPoint(p.alertDate(), p.criticalCount()))
                .toList();
        StockoutFrequencyKpi stockoutKpi = new StockoutFrequencyKpi(
                domain.stockoutFrequency().last30Days(),
                DirectionTrend.valueOf(domain.stockoutFrequency().trend().name()),
                stockoutHistory
        );

        List<CycleTimeDataPoint> cycleHistory = domain.replenishmentCycleTime().history().stream()
                .map(p -> new CycleTimeDataPoint(p.weekStart(), p.averageDays().doubleValue(), p.poCount()))
                .toList();
        ReplenishmentCycleTimeKpi cycleKpi = new ReplenishmentCycleTimeKpi(
                domain.replenishmentCycleTime().averageDays().doubleValue(),
                Trend.valueOf(domain.replenishmentCycleTime().trend().name()),
                cycleHistory
        );

        OnTimeDeliveryKpi otdKpi = new OnTimeDeliveryKpi(
                domain.onTimeDelivery().rate().doubleValue(),
                Trend.valueOf(domain.onTimeDelivery().trend().name())
        );

        List<SupplierPerformanceEntry> supplierKpis = domain.supplierPerformance().stream()
                .map(s -> new SupplierPerformanceEntry(
                        s.supplierId(),
                        s.supplierName(),
                        s.otdRate().doubleValue(),
                        s.fillRate().doubleValue(),
                        s.earlyCount(),
                        s.onTimeCount(),
                        s.lateCount(),
                        s.openExceptions()
                ))
                .toList();

        ExecutiveKpis kpis = new ExecutiveKpis(forecastKpi, stockoutKpi, cycleKpi, otdKpi, supplierKpis);
        OffsetDateTime freshness = domain.dataFreshness().atOffset(ZoneOffset.UTC);
        return new ExecutiveDashboardResponse(kpis, freshness);
    }
}
