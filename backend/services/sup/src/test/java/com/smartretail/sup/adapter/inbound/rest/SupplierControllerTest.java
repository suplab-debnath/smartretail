package com.smartretail.sup.adapter.inbound.rest;

import com.smartretail.sup.domain.model.SupplierRecordList;
import com.smartretail.sup.port.inbound.SupplierQueryPort;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SupplierControllerTest {

    @Mock private SupplierQueryPort supplierQueryPort;
    @Mock private HttpServletRequest httpRequest;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SupplierController controller = new SupplierController(supplierQueryPort);
        ReflectionTestUtils.setField(controller, "httpRequest", httpRequest);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getSuppliers_withPlannerRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        UUID id = UUID.randomUUID();
        SupplierRecordList list = new SupplierRecordList(
                List.of(new SupplierRecordList.SupplierEntry(id, "Acme Beverages")));
        when(supplierQueryPort.getSuppliers()).thenReturn(list);

        mockMvc.perform(get("/v1/supplier/suppliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suppliers[0].supplierName").value("Acme Beverages"))
                .andExpect(jsonPath("$.suppliers[0].supplierId").value(id.toString()));
    }

    @Test
    void getSuppliers_withSupplierAdminRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SUPPLIER_ADMIN");
        when(supplierQueryPort.getSuppliers())
                .thenReturn(new SupplierRecordList(List.of()));

        mockMvc.perform(get("/v1/supplier/suppliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suppliers").isArray());
    }

    @Test
    void getSuppliers_withUnauthorisedRole_returns403() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("STORE_MANAGER");

        mockMvc.perform(get("/v1/supplier/suppliers"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(supplierQueryPort);
    }

    @Test
    void getSuppliers_emptyList_returnsEmptyArray() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("ADMIN");
        when(supplierQueryPort.getSuppliers())
                .thenReturn(new SupplierRecordList(List.of()));

        mockMvc.perform(get("/v1/supplier/suppliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suppliers").isEmpty());
    }

    @Test
    void getSuppliers_withJwtCognitoGroup_returns200() throws Exception {
        setJwtAuth("SC_PLANNER");
        when(supplierQueryPort.getSuppliers()).thenReturn(new SupplierRecordList(List.of()));

        mockMvc.perform(get("/v1/supplier/suppliers"))
                .andExpect(status().isOk());
    }

    @Test
    void getSuppliers_withJwtUnauthorisedGroup_returns403() throws Exception {
        setJwtAuth("EXECUTIVE");

        mockMvc.perform(get("/v1/supplier/suppliers"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(supplierQueryPort);
    }

    @Test
    void getSuppliers_withJwtNullGroups_returns403() throws Exception {
        setJwtAuthNullGroups();

        mockMvc.perform(get("/v1/supplier/suppliers"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(supplierQueryPort);
    }

    @Test
    void getSuppliers_withNullDevRoleHeader_returns403() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn(null);

        mockMvc.perform(get("/v1/supplier/suppliers"))
                .andExpect(status().isForbidden());
    }

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
}
