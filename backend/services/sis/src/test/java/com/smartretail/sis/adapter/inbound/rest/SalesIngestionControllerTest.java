package com.smartretail.sis.adapter.inbound.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartretail.sis.domain.model.IngestionResult;
import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.inbound.SalesEventPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SalesIngestionControllerTest {

    @Mock
    private SalesEventPort salesEventPort;
    @Mock
    private SalesEventMapper salesEventMapper;

    private MockMvc mvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        SalesIngestionController controller = new SalesIngestionController(salesEventPort, salesEventMapper);
        mvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void validRequest_returns202WithAcceptedStatus() throws Exception {
        UUID txId = UUID.randomUUID();
        when(salesEventMapper.toDomain(any())).thenReturn(sampleTransaction(txId));
        when(salesEventPort.ingest(any())).thenReturn(new IngestionResult.Accepted(txId));

        mvc.perform(post("/v1/ingest/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson(txId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.transactionId").value(txId.toString()));
    }

    @Test
    void duplicateEvent_returns409() throws Exception {
        UUID txId = UUID.randomUUID();
        when(salesEventMapper.toDomain(any())).thenReturn(sampleTransaction(txId));
        when(salesEventPort.ingest(any())).thenReturn(new IngestionResult.Duplicate(txId));

        mvc.perform(post("/v1/ingest/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson(txId)))
                .andExpect(status().isConflict());
    }

    @Test
    void missingRequiredFields_returns400() throws Exception {
        mvc.perform(post("/v1/ingest/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":\"STORE-001\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String validRequestJson(UUID txId) {
        return "{\"transactionId\":\"" + txId + "\"," +
                "\"storeId\":\"STORE-001\",\"skuId\":\"SKU-BEV-001\",\"dcId\":\"DC-LONDON\"," +
                "\"quantity\":30,\"unitPrice\":8.50,\"channel\":\"POS\"," +
                "\"eventTimestamp\":\"2026-05-15T14:23:00Z\"}";
    }

    private SalesTransaction sampleTransaction(UUID txId) {
        return new SalesTransaction(
                txId, "STORE-001", "SKU-BEV-001", "DC-LONDON",
                30, BigDecimal.valueOf(8.50), SalesTransaction.Channel.POS,
                Instant.parse("2026-05-15T14:23:00Z")
        );
    }
}
