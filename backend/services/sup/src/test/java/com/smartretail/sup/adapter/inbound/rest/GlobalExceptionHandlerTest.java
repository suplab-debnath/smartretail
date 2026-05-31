package com.smartretail.sup.adapter.inbound.rest;

import com.smartretail.sup.domain.model.SupplierRecordList;
import com.smartretail.sup.port.inbound.SupplierQueryPort;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

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

    @Test
    void handleUnexpected_serviceThrows_returns500WithErrorBody() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("ADMIN");
        when(supplierQueryPort.getSuppliers())
                .thenThrow(new RuntimeException("database connection lost"));

        mockMvc.perform(get("/v1/supplier/suppliers"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void handleUnexpected_runtimeException_returns500() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        when(supplierQueryPort.getSuppliers())
                .thenThrow(new IllegalStateException("unexpected state"));

        mockMvc.perform(get("/v1/supplier/suppliers"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
