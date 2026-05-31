package com.smartretail.dfs.adapter.inbound.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartretail.dfs.domain.model.ForecastData;
import com.smartretail.dfs.domain.model.ForecastIngestionResult;
import com.smartretail.dfs.domain.model.ForecastRow;
import com.smartretail.dfs.port.inbound.ForecastQueryPort;
import com.smartretail.dfs.port.inbound.ForecastTriggerPort;
import com.smartretail.dfs.port.inbound.ForecastWritePort;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ForecastControllerTest {

    @Mock private ForecastQueryPort forecastQueryPort;
    @Mock private ForecastTriggerPort forecastTriggerPort;
    @Mock private ForecastWritePort forecastWritePort;
    @Mock private HttpServletRequest httpRequest;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ForecastResponseMapper mapper = Mappers.getMapper(ForecastResponseMapper.class);
        ForecastController controller = new ForecastController(
                forecastQueryPort, mapper, forecastWritePort, forecastTriggerPort);
        ReflectionTestUtils.setField(controller, "httpRequest", httpRequest);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── GET /v1/forecast/bands ────────────────────────────────────────────────

    @Test
    void getForecastBands_withPlannerRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        when(forecastQueryPort.getForecast(eq("SKU-BEV-001"), eq("DC-LONDON"), eq(30)))
                .thenReturn(minimalForecastData());

        mockMvc.perform(get("/v1/forecast/{skuId}/{dcId}", "SKU-BEV-001", "DC-LONDON")
                        .param("horizonDays", "30")
                        .header("X-Dev-Role", "SC_PLANNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skuId").value("SKU-BEV-001"))
                .andExpect(jsonPath("$.dcId").value("DC-LONDON"));
    }

    @Test
    void getForecastBands_withAdminRole_returns200() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("ADMIN");
        when(forecastQueryPort.getForecast(any(), any(), anyInt()))
                .thenReturn(minimalForecastData());

        mockMvc.perform(get("/v1/forecast/{skuId}/{dcId}", "SKU-BEV-001", "DC-LONDON")
                        .param("horizonDays", "30"))
                .andExpect(status().isOk());
    }

    @Test
    void getForecastBands_withUnauthorisedRole_returns403() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("STORE_MANAGER");

        mockMvc.perform(get("/v1/forecast/{skuId}/{dcId}", "SKU-BEV-001", "DC-LONDON")
                        .param("horizonDays", "30"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(forecastQueryPort);
    }

    @Test
    void getForecastBands_noBandsFound_returns404() throws Exception {
        when(httpRequest.getHeader("X-Dev-Role")).thenReturn("SC_PLANNER");
        ForecastData empty = new ForecastData(
                "SKU-999", "DC-UNKNOWN", 30, BigDecimal.ZERO, List.of(), Instant.now());
        when(forecastQueryPort.getForecast(any(), any(), anyInt())).thenReturn(empty);

        mockMvc.perform(get("/v1/forecast/{skuId}/{dcId}", "SKU-999", "DC-UNKNOWN")
                        .param("horizonDays", "30"))
                .andExpect(status().isNotFound());
    }

    // ── POST /v1/forecast/runs ────────────────────────────────────────────────

    @Test
    void triggerForecastRun_scheduledTrigger_returns201WithRunId() throws Exception {
        UUID runId = UUID.randomUUID();
        when(forecastTriggerPort.registerRun("SCHEDULED")).thenReturn(runId);

        String body = """
                {"triggeredBy": "SCHEDULED"}
                """;

        mockMvc.perform(post("/v1/forecast/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("TRIGGERED"));
    }

    @Test
    void triggerForecastRun_manualTrigger_returns201() throws Exception {
        UUID runId = UUID.randomUUID();
        when(forecastTriggerPort.registerRun("MANUAL")).thenReturn(runId);

        String body = """
                {"triggeredBy": "MANUAL"}
                """;

        mockMvc.perform(post("/v1/forecast/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.triggeredBy").value("MANUAL"));
    }

    // ── POST /v1/forecast/runs/{runId}/results ────────────────────────────────

    @Test
    void ingestForecastResults_validRequest_returns201() throws Exception {
        UUID runId = UUID.randomUUID();
        ForecastIngestionResult result = new ForecastIngestionResult(runId, 2, Instant.now());
        when(forecastWritePort.ingest(eq(runId), anyList())).thenReturn(result);

        String body = """
                {
                  "rows": [
                    {
                      "skuId": "SKU-BEV-001",
                      "dcId": "DC-LONDON",
                      "forecastDate": "2026-06-01",
                      "horizonDays": 30,
                      "p10": 80,
                      "p50": 100,
                      "p90": 120
                    },
                    {
                      "skuId": "SKU-BEV-001",
                      "dcId": "DC-LONDON",
                      "forecastDate": "2026-06-02",
                      "horizonDays": 30,
                      "p10": 85,
                      "p50": 105,
                      "p90": 125
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/v1/forecast/runs/{runId}/results", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.rowsInserted").value(2));
    }

    // ── Helper factories ──────────────────────────────────────────────────────

    private ForecastData minimalForecastData() {
        var band = new ForecastData.Band(LocalDate.now().plusDays(1), 80, 100, 120, null);
        return new ForecastData(
                "SKU-BEV-001", "DC-LONDON", 30,
                BigDecimal.valueOf(10.5), List.of(band), Instant.now());
    }
}
