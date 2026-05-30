package com.smartretail.re.adapter.inbound.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartretail.re.adapter.inbound.rest.generated.model.ApproveRequest;
import com.smartretail.re.adapter.inbound.rest.generated.model.ManualReplenishmentRequest;
import com.smartretail.re.adapter.inbound.rest.generated.model.RejectRequest;
import com.smartretail.re.domain.model.PoLineItem;
import com.smartretail.re.domain.model.WorkflowStatus;
import com.smartretail.re.domain.model.exception.PurchaseOrderNotFoundException;
import com.smartretail.re.port.inbound.ApprovePurchaseOrderPort;
import com.smartretail.re.port.inbound.RejectPurchaseOrderPort;
import com.smartretail.re.port.inbound.TriggerManualReplenishmentPort;
import com.smartretail.re.port.outbound.ReplenishmentRepositoryPort;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReplenishmentControllerTest {

    @Mock
    private ReplenishmentRepositoryPort repo;

    @Mock
    private ApprovePurchaseOrderPort approvePort;

    @Mock
    private RejectPurchaseOrderPort rejectPort;

    @Mock
    private TriggerManualReplenishmentPort triggerPort;

    @Mock
    private HttpServletRequest httpRequest;

    private final ReplenishmentResponseMapper mapper = Mappers.getMapper(ReplenishmentResponseMapper.class);

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        ReplenishmentController controller = new ReplenishmentController(
                repo, approvePort, rejectPort, triggerPort, mapper, httpRequest
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Helper generators ────────────────────────────────────────────────────

    private com.smartretail.re.domain.model.PurchaseOrder sampleDomainPo(UUID poId) {
        var po = new com.smartretail.re.domain.model.PurchaseOrder();
        po.setPoId(poId);
        po.setRuleId(UUID.randomUUID());
        po.setSupplierId("supplier-1");
        po.setSkuId("SKU-BEV-001");
        po.setDcId("DC-LONDON");
        po.setQuantity(100);
        po.setTotalValue(new BigDecimal("850.00"));
        po.setWorkflowStatus(WorkflowStatus.PENDING_APPROVAL);
        po.setVersion(1);
        po.setCreatedAt(Instant.parse("2026-05-30T10:00:00Z"));
        po.setUpdatedAt(Instant.parse("2026-05-30T10:00:00Z"));
        return po;
    }

    private List<PoLineItem> sampleLineItems(UUID poId) {
        return List.of(
                new PoLineItem(UUID.randomUUID(), poId, "SKU-BEV-001", 100, new BigDecimal("8.50"), new BigDecimal("850.00"))
        );
    }

    // ── GET /v1/replenishment/orders/{poId} ──────────────────────────────────

    @Test
    void getPurchaseOrder_whenExists_returnsPo() throws Exception {
        UUID poId = UUID.randomUUID();
        com.smartretail.re.domain.model.PurchaseOrder domainPo = sampleDomainPo(poId);
        List<PoLineItem> lineItems = sampleLineItems(poId);

        when(repo.findById(poId)).thenReturn(Optional.of(domainPo));
        when(repo.findLineItemsByPoId(poId)).thenReturn(lineItems);

        mockMvc.perform(get("/v1/replenishment/orders/{poId}", poId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poId").value(poId.toString()))
                .andExpect(jsonPath("$.skuId").value("SKU-BEV-001"))
                .andExpect(jsonPath("$.workflowStatus").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.lineItems[0].skuId").value("SKU-BEV-001"))
                .andExpect(jsonPath("$.lineItems[0].lineTotal").value(850.00));
    }

    @Test
    void getPurchaseOrder_whenDoesNotExist_returns404() throws Exception {
        UUID poId = UUID.randomUUID();
        when(repo.findById(poId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/replenishment/orders/{poId}", poId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    // ── GET /v1/replenishment/orders ─────────────────────────────────────────

    @Test
    void listPurchaseOrders_returnsPagedOrders() throws Exception {
        UUID poId = UUID.randomUUID();
        com.smartretail.re.domain.model.PurchaseOrder po = sampleDomainPo(poId);

        when(repo.findOrders(eq("APPROVED"), eq("DC-LONDON"), eq("SKU-BEV-001"), eq(0), eq(10)))
                .thenReturn(List.of(po));
        when(repo.countOrders(eq("APPROVED"), eq("DC-LONDON"), eq("SKU-BEV-001")))
                .thenReturn(1L);

        mockMvc.perform(get("/v1/replenishment/orders")
                        .param("status", "APPROVED")
                        .param("dcId", "DC-LONDON")
                        .param("skuId", "SKU-BEV-001")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].poId").value(poId.toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ── POST /v1/replenishment/orders ────────────────────────────────────────

    @Test
    void createPurchaseOrder_triggersAndReturnsCreated() throws Exception {
        UUID poId = UUID.randomUUID();
        com.smartretail.re.domain.model.PurchaseOrder po = sampleDomainPo(poId);
        List<PoLineItem> lineItems = sampleLineItems(poId);

        ManualReplenishmentRequest request = new ManualReplenishmentRequest();
        request.setSkuId("SKU-BEV-001");
        request.setDcId("DC-LONDON");
        request.setQuantity(100);
        request.setNotes("Urgent restock");

        when(triggerPort.trigger("SKU-BEV-001", "DC-LONDON", 100, "Urgent restock")).thenReturn(po);
        when(repo.findLineItemsByPoId(poId)).thenReturn(lineItems);

        mockMvc.perform(post("/v1/replenishment/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.poId").value(poId.toString()))
                .andExpect(jsonPath("$.quantity").value(100))
                .andExpect(jsonPath("$.lineItems[0].skuId").value("SKU-BEV-001"));
    }

    // ── POST /v1/replenishment/orders/{poId}/approve ─────────────────────────

    @Test
    void approvePurchaseOrder_withDevRoleHeader_returnsApprovedPo() throws Exception {
        UUID poId = UUID.randomUUID();
        com.smartretail.re.domain.model.PurchaseOrder po = sampleDomainPo(poId);
        po.setWorkflowStatus(WorkflowStatus.APPROVED);
        po.setApprovedBy("local-user");
        po.setApprovedAt(Instant.now());

        ApproveRequest request = new ApproveRequest();
        request.setVersion(1);

        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        when(approvePort.approve(poId, 1, "local-user")).thenReturn(po);
        when(repo.findLineItemsByPoId(poId)).thenReturn(sampleLineItems(poId));

        mockMvc.perform(post("/v1/replenishment/orders/{poId}/approve", poId)
                        .header("X-Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("local-user"));
    }

    @Test
    void approvePurchaseOrder_withCognitoJwtRole_returnsApprovedPo() throws Exception {
        UUID poId = UUID.randomUUID();
        com.smartretail.re.domain.model.PurchaseOrder po = sampleDomainPo(poId);
        po.setWorkflowStatus(WorkflowStatus.APPROVED);
        po.setApprovedBy("jwt-planner");
        po.setApprovedAt(Instant.now());

        ApproveRequest request = new ApproveRequest();
        request.setVersion(1);

        // Real JWT and SecurityContext
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("cognito:groups", List.of("SC_PLANNER"))
                .subject("jwt-planner")
                .build();

        Authentication auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                jwt, null, List.of()
        ) {
            @Override
            public String getName() {
                return "jwt-planner";
            }
        };

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        SecurityContextHolder.setContext(securityContext);

        when(approvePort.approve(poId, 1, "jwt-planner")).thenReturn(po);
        when(repo.findLineItemsByPoId(poId)).thenReturn(sampleLineItems(poId));

        mockMvc.perform(post("/v1/replenishment/orders/{poId}/approve", poId)
                        .header("X-Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("jwt-planner"));
    }

    @Test
    void approvePurchaseOrder_withInsufficientDevRole_returns403() throws Exception {
        UUID poId = UUID.randomUUID();
        ApproveRequest request = new ApproveRequest();
        request.setVersion(1);

        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("STORE_ASSOCIATE");

        mockMvc.perform(post("/v1/replenishment/orders/{poId}/approve", poId)
                        .header("X-Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));

        verify(approvePort, never()).approve(any(), anyInt(), any());
    }

    // ── POST /v1/replenishment/orders/{poId}/reject ──────────────────────────

    @Test
    void rejectPurchaseOrder_withDevRoleHeader_returnsRejectedPo() throws Exception {
        UUID poId = UUID.randomUUID();
        com.smartretail.re.domain.model.PurchaseOrder po = sampleDomainPo(poId);
        po.setWorkflowStatus(WorkflowStatus.REJECTED);
        po.setRejectedBy("local-user");
        po.setRejectedAt(Instant.now());
        po.setRejectionReason("Price too high");

        RejectRequest request = new RejectRequest();
        request.setVersion(1);
        request.setRejectionReason("Price too high");

        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("ADMIN");
        when(rejectPort.reject(poId, 1, "local-user", "Price too high")).thenReturn(po);
        when(repo.findLineItemsByPoId(poId)).thenReturn(sampleLineItems(poId));

        mockMvc.perform(post("/v1/replenishment/orders/{poId}/reject", poId)
                        .header("X-Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("REJECTED"))
                .andExpect(jsonPath("$.rejectedBy").value("local-user"))
                .andExpect(jsonPath("$.rejectionReason").value("Price too high"));
    }

    @Test
    void rejectPurchaseOrder_withInsufficientRole_returns403() throws Exception {
        UUID poId = UUID.randomUUID();
        RejectRequest request = new RejectRequest();
        request.setVersion(1);
        request.setRejectionReason("Price too high");

        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("STORE_ASSOCIATE");

        mockMvc.perform(post("/v1/replenishment/orders/{poId}/reject", poId)
                        .header("X-Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));

        verify(rejectPort, never()).reject(any(), anyInt(), any(), any());
    }
}
