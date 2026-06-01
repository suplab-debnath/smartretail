package com.smartretail.ars.adapter.inbound.rest;

import com.smartretail.ars.domain.model.ExecutiveDashboard;
import com.smartretail.ars.domain.model.ScPlannerDashboard;
import com.smartretail.ars.domain.model.StoreManagerDashboard;
import com.smartretail.ars.domain.model.SupplierOrdersDashboard;
import com.smartretail.ars.domain.model.SupplierPerformanceDashboard;
import com.smartretail.ars.port.inbound.ExecutiveDashboardPort;
import com.smartretail.ars.port.inbound.ScPlannerDashboardPort;
import com.smartretail.ars.port.inbound.StoreManagerDashboardPort;
import com.smartretail.ars.port.inbound.SupplierOrdersDashboardPort;
import com.smartretail.ars.port.inbound.SupplierPerformancePort;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock private ExecutiveDashboardPort executiveDashboardPort;
    @Mock private ScPlannerDashboardPort scPlannerDashboardPort;
    @Mock private SupplierPerformancePort supplierPerformancePort;
    @Mock private SupplierOrdersDashboardPort supplierOrdersDashboardPort;
    @Mock private StoreManagerDashboardPort storeManagerDashboardPort;
    @Mock private HttpServletRequest httpRequest;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DashboardResponseMapper dashMapper = Mappers.getMapper(DashboardResponseMapper.class);
        StoreManagerResponseMapper smMapper = Mappers.getMapper(StoreManagerResponseMapper.class);

        DashboardController controller = new DashboardController(
                executiveDashboardPort, scPlannerDashboardPort,
                supplierPerformancePort, supplierOrdersDashboardPort,
                storeManagerDashboardPort, dashMapper, smMapper);

        ReflectionTestUtils.setField(controller, "httpRequest", httpRequest);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Executive Dashboard ───────────────────────────────────────────────────

    @Test
    void getExecutiveDashboard_withExecutiveRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("EXECUTIVE");
        when(executiveDashboardPort.assemble()).thenReturn(minimalExecutiveDashboard());

        mockMvc.perform(get("/v1/dashboard/executive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis").exists());
    }

    @Test
    void getExecutiveDashboard_withAdminRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("ADMIN");
        when(executiveDashboardPort.assemble()).thenReturn(minimalExecutiveDashboard());

        mockMvc.perform(get("/v1/dashboard/executive"))
                .andExpect(status().isOk());
    }

    @Test
    void getExecutiveDashboard_withStoreManagerRole_returns403() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("STORE_MANAGER");

        mockMvc.perform(get("/v1/dashboard/executive"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(executiveDashboardPort);
    }

    // ── Store Manager Dashboard ───────────────────────────────────────────────

    @Test
    void getStoreManagerDashboard_withStoreManagerRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("STORE_MANAGER");
        when(storeManagerDashboardPort.assemble(eq("DC-LONDON"), eq(0), eq(10)))
                .thenReturn(minimalStoreManagerDashboard("DC-LONDON"));

        mockMvc.perform(get("/v1/dashboard/store-manager")
                        .param("dcId", "DC-LONDON")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dcId").value("DC-LONDON"));
    }

    @Test
    void getStoreManagerDashboard_withScPlannerRole_returns403() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");

        mockMvc.perform(get("/v1/dashboard/store-manager")
                        .param("dcId", "DC-LONDON")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(storeManagerDashboardPort);
    }

    // ── SC Planner Dashboard ──────────────────────────────────────────────────

    @Test
    void getScPlannerDashboard_withPlannerRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        when(scPlannerDashboardPort.assemble()).thenReturn(minimalScPlannerDashboard());

        mockMvc.perform(get("/v1/dashboard/sc-planner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingApprovalCount").value(3));
    }

    @Test
    void getScPlannerDashboard_withExecutiveRole_returns403() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("EXECUTIVE");

        mockMvc.perform(get("/v1/dashboard/sc-planner"))
                .andExpect(status().isForbidden());
    }

    // ── Supplier Orders Dashboard ─────────────────────────────────────────────

    @Test
    void getSupplierOrdersDashboard_withAdminRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("ADMIN");
        when(supplierOrdersDashboardPort.assemble(isNull()))
                .thenReturn(new SupplierOrdersDashboard(List.of(), Instant.now()));

        mockMvc.perform(get("/v1/dashboard/supplier-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").isArray());
    }

    // ── Supplier Performance Dashboard ────────────────────────────────────────

    @Test
    void getSupplierPerformanceDashboard_withPlannerRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        when(supplierPerformancePort.assemble())
                .thenReturn(new SupplierPerformanceDashboard(List.of(), Instant.now()));

        mockMvc.perform(get("/v1/dashboard/supplier-performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suppliers").isArray());
    }

    // ── JWT auth path ─────────────────────────────────────────────────────────

    @Test
    void getExecutiveDashboard_withJwtCognitoGroup_returns200() throws Exception {
        when(executiveDashboardPort.assemble()).thenReturn(minimalExecutiveDashboard());
        setJwtAuth("EXECUTIVE");

        mockMvc.perform(get("/v1/dashboard/executive"))
                .andExpect(status().isOk());
    }

    @Test
    void getExecutiveDashboard_withJwtNullGroups_returns403() throws Exception {
        setJwtAuthNullGroups();

        mockMvc.perform(get("/v1/dashboard/executive"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(executiveDashboardPort);
    }

    @Test
    void getExecutiveDashboard_withScPlannerRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        when(executiveDashboardPort.assemble()).thenReturn(minimalExecutiveDashboard());

        mockMvc.perform(get("/v1/dashboard/executive"))
                .andExpect(status().isOk());
    }

    @Test
    void getExecutiveDashboard_withNullHeader_defaultsToExecutiveRoleAndReturns200() throws Exception {
        // null X-Dev-Role header → extractRoles() returns {"EXECUTIVE"} → allowed
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn(null);
        when(executiveDashboardPort.assemble()).thenReturn(minimalExecutiveDashboard());

        mockMvc.perform(get("/v1/dashboard/executive"))
                .andExpect(status().isOk());
    }

    @Test
    void getScPlannerDashboard_withNullHeader_returns403() throws Exception {
        // null header → EXECUTIVE default → not in PLANNER_ROLES → 403
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn(null);

        mockMvc.perform(get("/v1/dashboard/sc-planner"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getStoreManagerDashboard_withAdminRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("ADMIN");
        // OpenAPI defaultValue="20" for size, so absent param arrives as 20
        when(storeManagerDashboardPort.assemble(eq("DC-LONDON"), eq(0), eq(20)))
                .thenReturn(minimalStoreManagerDashboard("DC-LONDON"));

        mockMvc.perform(get("/v1/dashboard/store-manager")
                        .param("dcId", "DC-LONDON"))
                .andExpect(status().isOk());
    }

    @Test
    void getStoreManagerDashboard_withNullPageAndSize_usesOpenApiDefaults() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("STORE_MANAGER");
        // OpenAPI defaults: page=0, size=20
        when(storeManagerDashboardPort.assemble(eq("DC-LONDON"), eq(0), eq(20)))
                .thenReturn(minimalStoreManagerDashboard("DC-LONDON"));

        mockMvc.perform(get("/v1/dashboard/store-manager")
                        .param("dcId", "DC-LONDON"))
                .andExpect(status().isOk());

        verify(storeManagerDashboardPort).assemble("DC-LONDON", 0, 20);
    }

    @Test
    void getScPlannerDashboard_withJwtCognitoGroup_returns200() throws Exception {
        when(scPlannerDashboardPort.assemble()).thenReturn(minimalScPlannerDashboard());
        setJwtAuth("SC_PLANNER");

        mockMvc.perform(get("/v1/dashboard/sc-planner"))
                .andExpect(status().isOk());
    }

    @Test
    void getSupplierOrdersDashboard_withStatusFilter_passesStatusToPort() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("ADMIN");
        when(supplierOrdersDashboardPort.assemble("DISPATCHED"))
                .thenReturn(new SupplierOrdersDashboard(List.of(), Instant.now()));

        mockMvc.perform(get("/v1/dashboard/supplier-orders")
                        .param("status", "DISPATCHED"))
                .andExpect(status().isOk());

        verify(supplierOrdersDashboardPort).assemble("DISPATCHED");
    }

    @Test
    void getSupplierOrdersDashboard_withExecutiveRole_returns403() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("EXECUTIVE");

        mockMvc.perform(get("/v1/dashboard/supplier-orders"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(supplierOrdersDashboardPort);
    }

    @Test
    void getSupplierPerformanceDashboard_withJwtUnauthorisedGroup_returns403() throws Exception {
        setJwtAuth("STORE_MANAGER");

        mockMvc.perform(get("/v1/dashboard/supplier-performance"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(supplierPerformancePort);
    }

    // ── Helper factories ──────────────────────────────────────────────────────

    private void setJwtAuth(String group) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("cognito:groups", List.of(group))
                .subject("test-user")
                .build();
        Authentication auth = new UsernamePasswordAuthenticationToken(jwt, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void setJwtAuthNullGroups() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("test-user")
                .build();
        Authentication auth = new UsernamePasswordAuthenticationToken(jwt, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private ExecutiveDashboard minimalExecutiveDashboard() {
        var forecastAccuracy = new ExecutiveDashboard.ForecastAccuracy(
                BigDecimal.valueOf(12.5),
                ExecutiveDashboard.Trend.STABLE,
                List.of());
        var stockout = new ExecutiveDashboard.StockoutFrequency(
                5, ExecutiveDashboard.DirectionTrend.STABLE, List.of());
        var cycleTime = new ExecutiveDashboard.ReplenishmentCycleTime(
                BigDecimal.valueOf(3.5), ExecutiveDashboard.Trend.IMPROVING, List.of());
        var otd = new ExecutiveDashboard.OnTimeDelivery(
                BigDecimal.valueOf(0.92), ExecutiveDashboard.Trend.STABLE);
        return new ExecutiveDashboard(forecastAccuracy, stockout, cycleTime, otd, List.of(), Instant.now());
    }

    private ScPlannerDashboard minimalScPlannerDashboard() {
        var acc = new ScPlannerDashboard.ForecastAccuracy(
                BigDecimal.valueOf(10.0),
                BigDecimal.valueOf(15.0),
                Instant.now(),
                ScPlannerDashboard.MapeStatus.WITHIN_THRESHOLD);
        return new ScPlannerDashboard(3, 7, acc, Instant.now());
    }

    private StoreManagerDashboard minimalStoreManagerDashboard(String dcId) {
        var kpi = new StoreManagerDashboard.AlertKpi(1, 2, 3);
        return new StoreManagerDashboard(
                dcId, kpi, 500L, 4,
                BigDecimal.valueOf(0.85), List.of(), 0, 1, Instant.now());
    }
}
