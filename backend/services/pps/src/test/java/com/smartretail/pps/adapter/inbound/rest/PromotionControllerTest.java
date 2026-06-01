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

    @Test
    void getPromotionSchedules_withJwtCognitoGroup_returns200() throws Exception {
        setJwtAuth("SC_PLANNER");
        when(promotionQueryPort.getPromotionSchedules(null))
                .thenReturn(new PromotionList(List.of(), Instant.now()));

        mockMvc.perform(get("/v1/promotions/schedules"))
                .andExpect(status().isOk());
    }

    @Test
    void getPromotionSchedules_withJwtUnauthorisedGroup_returns403() throws Exception {
        setJwtAuth("STORE_MANAGER");

        mockMvc.perform(get("/v1/promotions/schedules"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(promotionQueryPort);
    }

    @Test
    void getPromotionSchedules_withJwtNullGroups_returns403() throws Exception {
        setJwtAuthNullGroups();

        mockMvc.perform(get("/v1/promotions/schedules"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(promotionQueryPort);
    }

    @Test
    void getPromotionSchedules_withNullDevRoleHeader_returns403() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn(null);

        mockMvc.perform(get("/v1/promotions/schedules"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPromotionSchedules_scheduleWithElasticityCoeff_mapsOptionalField() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        when(promotionQueryPort.getPromotionSchedules(null)).thenReturn(promotionListWithElasticity());

        mockMvc.perform(get("/v1/promotions/schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules[0].elasticityCoeff").value(1.25));
    }

    @Test
    void getPromotionSchedules_scheduleWithNullElasticityAndNullSourceEventId_omitsOptionalFields() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        when(promotionQueryPort.getPromotionSchedules(null)).thenReturn(promotionListNullOptionals());

        mockMvc.perform(get("/v1/promotions/schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules[0].elasticityCoeff").doesNotExist());
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

    private PromotionList promotionListWithElasticity() {
        var schedule = new PromotionList.PromotionSchedule(
                UUID.randomUUID(), "Winter Promo",
                List.of("SKU-SNK-001"), List.of("DC-LONDON"),
                10.0, 1.2, 1.25,
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31),
                "ACTIVE", UUID.randomUUID());
        return new PromotionList(List.of(schedule), Instant.now());
    }

    private PromotionList promotionListNullOptionals() {
        var schedule = new PromotionList.PromotionSchedule(
                UUID.randomUUID(), "Clearance",
                List.of("SKU-DRY-001"), List.of("DC-BIRMINGHAM"),
                20.0, 1.0, null,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15),
                "ACTIVE", null);
        return new PromotionList(List.of(schedule), Instant.now());
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
