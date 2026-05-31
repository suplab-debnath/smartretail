package com.smartretail.pps.adapter.inbound.rest;

import com.smartretail.pps.domain.model.PromotionList;
import com.smartretail.pps.port.inbound.PromotionQueryPort;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class PromotionControllerTest {

    @Mock private PromotionQueryPort promotionQueryPort;
    @Mock private HttpServletRequest httpRequest;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PromotionResponseMapper mapper = Mappers.getMapper(PromotionResponseMapper.class);
        PromotionController controller = new PromotionController(promotionQueryPort, mapper);
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
    void getPromotionSchedules_withPlannerRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        when(promotionQueryPort.getPromotionSchedules(null)).thenReturn(promotionList());

        mockMvc.perform(get("/v1/promotions/schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules").isArray())
                .andExpect(jsonPath("$.schedules[0].promotionName").value("Summer Promo"));
    }

    @Test
    void getPromotionSchedules_withAdminRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("ADMIN");
        when(promotionQueryPort.getPromotionSchedules(null))
                .thenReturn(new PromotionList(List.of(), Instant.now()));

        mockMvc.perform(get("/v1/promotions/schedules"))
                .andExpect(status().isOk());
    }

    @Test
    void getPromotionSchedules_withUnauthorisedRole_returns403() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("STORE_MANAGER");

        mockMvc.perform(get("/v1/promotions/schedules"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(promotionQueryPort);
    }

    @Test
    void getPromotionSchedules_filteredByStatus_passesStatusToPort() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        when(promotionQueryPort.getPromotionSchedules("ACTIVE"))
                .thenReturn(new PromotionList(List.of(), Instant.now()));

        mockMvc.perform(get("/v1/promotions/schedules").param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules").isArray());

        verify(promotionQueryPort).getPromotionSchedules("ACTIVE");
    }

    @Test
    void getPromotionSchedules_emptyList_returnsEmptyArray() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("ADMIN");
        when(promotionQueryPort.getPromotionSchedules(null))
                .thenReturn(new PromotionList(List.of(), Instant.now()));

        mockMvc.perform(get("/v1/promotions/schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules").isEmpty());
    }

    private PromotionList promotionList() {
        var schedule = new PromotionList.PromotionSchedule(
                UUID.randomUUID(), "Summer Promo",
                List.of("SKU-BEV-001"), List.of("DC-LONDON"),
                15.0, 1.0, null,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                "ACTIVE", UUID.randomUUID());
        return new PromotionList(List.of(schedule), Instant.now());
    }
}
