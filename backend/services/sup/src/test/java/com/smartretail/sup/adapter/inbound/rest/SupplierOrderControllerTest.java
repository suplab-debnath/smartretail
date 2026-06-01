package com.smartretail.sup.adapter.inbound.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartretail.sup.domain.model.SupplierOrderList;
import com.smartretail.sup.port.inbound.CreateSupplierOrderPort;
import com.smartretail.sup.port.inbound.SupplierOrderQueryPort;
import com.smartretail.sup.port.outbound.SupplierOrderWritePort.DuplicatePoException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SupplierOrderControllerTest {

    @Mock private SupplierOrderQueryPort supplierOrderQueryPort;
    @Mock private CreateSupplierOrderPort createSupplierOrderPort;
    @Mock private HttpServletRequest httpRequest;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        SupplierOrderResponseMapper mapper = Mappers.getMapper(SupplierOrderResponseMapper.class);
        SupplierOrderController controller = new SupplierOrderController(
                supplierOrderQueryPort, createSupplierOrderPort, mapper);
        ReflectionTestUtils.setField(controller, "httpRequest", httpRequest);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── GET /v1/supplier/orders ───────────────────────────────────────────────

    @Test
    void getSupplierOrders_withPlannerRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        when(supplierOrderQueryPort.getSupplierOrders(null))
                .thenReturn(minimalOrderList());

        mockMvc.perform(get("/v1/supplier/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").isArray())
                .andExpect(jsonPath("$.orders[0].skuId").value("SKU-BEV-001"));
    }

    @Test
    void getSupplierOrders_filteredByStatus_passesStatusToPort() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("ADMIN");
        when(supplierOrderQueryPort.getSupplierOrders("PENDING"))
                .thenReturn(new SupplierOrderList(List.of(), Instant.now()));

        mockMvc.perform(get("/v1/supplier/orders")
                        .param("status", "PENDING"))
                .andExpect(status().isOk());

        verify(supplierOrderQueryPort).getSupplierOrders("PENDING");
    }

    @Test
    void getSupplierOrders_withUnauthorisedRole_returns403() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("STORE_MANAGER");

        mockMvc.perform(get("/v1/supplier/orders"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(supplierOrderQueryPort);
    }

    // ── POST /v1/supplier/orders ──────────────────────────────────────────────

    @Test
    void createSupplierOrder_validRequest_returns201WithSupplierPoId() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        UUID supplierPoId = UUID.randomUUID();
        when(createSupplierOrderPort.createSupplierOrder(any())).thenReturn(supplierPoId);

        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        String body = """
                {
                  "poId": "%s",
                  "supplierId": "%s",
                  "skuId": "SKU-BEV-001",
                  "dcId": "DC-LONDON",
                  "quantity": 500
                }
                """.formatted(poId, supplierId);

        mockMvc.perform(post("/v1/supplier/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierPoId").value(supplierPoId.toString()));
    }

    @Test
    void createSupplierOrder_duplicatePo_returns409() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        UUID poId = UUID.randomUUID();
        when(createSupplierOrderPort.createSupplierOrder(any()))
                .thenThrow(new DuplicatePoException(poId));

        UUID supplierId = UUID.randomUUID();
        String body = """
                {
                  "poId": "%s",
                  "supplierId": "%s",
                  "skuId": "SKU-BEV-001",
                  "dcId": "DC-LONDON",
                  "quantity": 500
                }
                """.formatted(poId, supplierId);

        mockMvc.perform(post("/v1/supplier/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void createSupplierOrder_withUnauthorisedRole_returns403() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("EXECUTIVE");

        UUID poId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        String body = """
                {
                  "poId": "%s",
                  "supplierId": "%s",
                  "skuId": "SKU-BEV-001",
                  "dcId": "DC-LONDON",
                  "quantity": 100
                }
                """.formatted(poId, supplierId);

        mockMvc.perform(post("/v1/supplier/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        verifyNoInteractions(createSupplierOrderPort);
    }

    // ── JWT auth branches ─────────────────────────────────────────────────────

    @Test
    void getSupplierOrders_withJwtCognitoGroup_returns200() throws Exception {
        setJwtAuth("SC_PLANNER");
        when(supplierOrderQueryPort.getSupplierOrders(null)).thenReturn(minimalOrderList());

        mockMvc.perform(get("/v1/supplier/orders"))
                .andExpect(status().isOk());
    }

    @Test
    void getSupplierOrders_withJwtUnauthorisedGroup_returns403() throws Exception {
        setJwtAuth("STORE_MANAGER");

        mockMvc.perform(get("/v1/supplier/orders"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(supplierOrderQueryPort);
    }

    @Test
    void getSupplierOrders_withJwtNullGroups_returns403() throws Exception {
        setJwtAuthNullGroups();

        mockMvc.perform(get("/v1/supplier/orders"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(supplierOrderQueryPort);
    }

    @Test
    void getSupplierOrders_withNullStatusParam_passesNullToPort() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("ADMIN");
        when(supplierOrderQueryPort.getSupplierOrders(null))
                .thenReturn(new SupplierOrderList(List.of(), Instant.now()));

        mockMvc.perform(get("/v1/supplier/orders"))
                .andExpect(status().isOk());

        verify(supplierOrderQueryPort).getSupplierOrders(null);
    }

    @Test
    void createSupplierOrder_withJwtCognitoGroup_returns201() throws Exception {
        setJwtAuth("SUPPLIER_ADMIN");
        UUID supplierPoId = UUID.randomUUID();
        when(createSupplierOrderPort.createSupplierOrder(any())).thenReturn(supplierPoId);

        String body = """
                {
                  "poId": "%s",
                  "supplierId": "%s",
                  "skuId": "SKU-BEV-001",
                  "dcId": "DC-LONDON",
                  "quantity": 100
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/v1/supplier/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
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

    private SupplierOrderList minimalOrderList() {
        var order = new SupplierOrderList.SupplierOrder(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Acme Beverages", "SKU-BEV-001", "DC-LONDON",
                500, "PENDING", null, null, LocalDate.now().plusDays(7), null);
        return new SupplierOrderList(List.of(order), Instant.now());
    }
}
