package com.smartretail.ars.adapter.inbound.rest;

import com.smartretail.ars.adapter.in.web.generated.api.DashboardApi;
import com.smartretail.ars.adapter.in.web.generated.model.ExecutiveDashboardResponse;
import com.smartretail.ars.adapter.in.web.generated.model.ScPlannerDashboardResponse;
import com.smartretail.ars.adapter.in.web.generated.model.StoreManagerDashboardResponse;
import com.smartretail.ars.adapter.in.web.generated.model.SupplierOrdersDashboardResponse;
import com.smartretail.ars.adapter.in.web.generated.model.SupplierPerformanceDashboardResponse;
import com.smartretail.ars.port.inbound.ExecutiveDashboardPort;
import com.smartretail.ars.port.inbound.ScPlannerDashboardPort;
import com.smartretail.ars.port.inbound.StoreManagerDashboardPort;
import com.smartretail.ars.port.inbound.SupplierOrdersDashboardPort;
import com.smartretail.ars.port.inbound.SupplierPerformancePort;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

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
    private final SupplierOrdersDashboardPort supplierOrdersDashboardPort;
    private final StoreManagerDashboardPort storeManagerDashboardPort;
    private final DashboardResponseMapper dashboardResponseMapper;
    private final StoreManagerResponseMapper storeManagerResponseMapper;

    @Autowired
    private HttpServletRequest httpRequest;

    public DashboardController(
            ExecutiveDashboardPort executiveDashboardPort,
            ScPlannerDashboardPort scPlannerDashboardPort,
            SupplierPerformancePort supplierPerformancePort,
            SupplierOrdersDashboardPort supplierOrdersDashboardPort,
            StoreManagerDashboardPort storeManagerDashboardPort,
            DashboardResponseMapper dashboardResponseMapper,
            StoreManagerResponseMapper storeManagerResponseMapper) {
        this.executiveDashboardPort       = executiveDashboardPort;
        this.scPlannerDashboardPort       = scPlannerDashboardPort;
        this.supplierPerformancePort      = supplierPerformancePort;
        this.supplierOrdersDashboardPort  = supplierOrdersDashboardPort;
        this.storeManagerDashboardPort    = storeManagerDashboardPort;
        this.dashboardResponseMapper      = dashboardResponseMapper;
        this.storeManagerResponseMapper   = storeManagerResponseMapper;
    }

    // ── Executive Dashboard (Flow 8) ─────────────────────────────────────────

    @Override
    public ResponseEntity<ExecutiveDashboardResponse> getExecutiveDashboard() {
        if (!hasAnyRole(EXECUTIVE_ROLES)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(dashboardResponseMapper.toExecutiveResponse(executiveDashboardPort.assemble()));
    }

    // ── Store Manager Dashboard (Flow 4) ─────────────────────────────────────

    @Override
    public ResponseEntity<StoreManagerDashboardResponse> getStoreManagerDashboard(
            String dcId, Integer page, Integer size) {
        if (!hasAnyRole(STORE_MANAGER_ROLES)) return ResponseEntity.status(403).build();
        int p = page != null ? page : 0;
        int s = size != null ? size : 10;
        return ResponseEntity.ok(
                storeManagerResponseMapper.toResponse(storeManagerDashboardPort.assemble(dcId, p, s))
        );
    }

    // ── SC Planner Dashboard (Flow 9) ────────────────────────────────────────

    @Override
    public ResponseEntity<ScPlannerDashboardResponse> getScPlannerDashboard() {
        if (!hasAnyRole(PLANNER_ROLES)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(dashboardResponseMapper.toScPlannerResponse(scPlannerDashboardPort.assemble()));
    }

    // ── Supplier Order Tracking (Flow 9) ────────────────────────────────────

    @Override
    public ResponseEntity<SupplierOrdersDashboardResponse> getSupplierOrdersDashboard(String status) {
        if (!hasAnyRole(PLANNER_ROLES)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(
                dashboardResponseMapper.toSupplierOrdersResponse(supplierOrdersDashboardPort.assemble(status)));
    }

    // ── Supplier Performance Scorecard (Flow 9) ──────────────────────────────

    @Override
    public ResponseEntity<SupplierPerformanceDashboardResponse> getSupplierPerformanceDashboard() {
        if (!hasAnyRole(PLANNER_ROLES)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(dashboardResponseMapper.toSupplierPerfResponse(supplierPerformancePort.assemble()));
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

}
