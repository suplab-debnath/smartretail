package com.smartretail.sis.adapter.inbound.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartretail.sis.domain.model.IngestionResult;
import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.inbound.SalesEventPort;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FirehoseBatchFilterTest {

    @Mock
    private SalesEventPort salesEventPort;
    @Mock
    private SalesEventMapper salesEventMapper;
    @Mock
    private FilterChain chain;

    private ObjectMapper objectMapper;
    private FirehoseBatchFilter filter;

    private static final String REQUEST_ID = "test-request-id-001";
    private static final String VALID_KEY   = "test-access-key";
    private static final String INGEST_PATH = "/v1/ingest/events";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        filter = new FirehoseBatchFilter(salesEventPort, salesEventMapper, objectMapper, VALID_KEY);
    }

    @Test
    void noFirehoseHeader_passesThroughToChain() throws Exception {
        MockHttpServletRequest req  = new MockHttpServletRequest("POST", INGEST_PATH);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verifyNoInteractions(salesEventPort);
    }

    @Test
    void differentPath_passesThroughToChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/other");
        req.addHeader("X-Amz-Firehose-Request-Id", REQUEST_ID);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verifyNoInteractions(salesEventPort);
    }

    @Test
    void validBatch_twoRecords_bothIngested_returns200() throws Exception {
        when(salesEventMapper.toDomain(any())).thenAnswer(inv -> sampleTransaction());
        when(salesEventPort.ingest(any())).thenReturn(new IngestionResult.Accepted(UUID.randomUUID()));

        MockHttpServletRequest req  = firehoseRequest(VALID_KEY, buildBatch(2));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(200);
        verify(salesEventPort, times(2)).ingest(any());
        verifyNoMoreInteractions(chain);
    }

    @Test
    void invalidBase64Record_skipped_validRecordStillProcessed() throws Exception {
        when(salesEventMapper.toDomain(any())).thenAnswer(inv -> sampleTransaction());
        when(salesEventPort.ingest(any())).thenReturn(new IngestionResult.Accepted(UUID.randomUUID()));

        String validData = encodeRecord(validSalesEventJson());
        String body = batchJson(REQUEST_ID,
                "[{\"data\":\"!!!not-valid-base64!!!\"},{\"data\":\"" + validData + "\"}]");

        MockHttpServletRequest req  = firehoseRequest(VALID_KEY, body);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(200);
        verify(salesEventPort, times(1)).ingest(any());
    }

    @Test
    void invalidJsonRecord_skipped_returns200() throws Exception {
        String invalidJson = Base64.getEncoder().encodeToString("{not-json}".getBytes(StandardCharsets.UTF_8));
        String body = batchJson(REQUEST_ID, "[{\"data\":\"" + invalidJson + "\"}]");

        MockHttpServletRequest req  = firehoseRequest(VALID_KEY, body);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(200);
        verifyNoInteractions(salesEventPort);
    }

    @Test
    void allDuplicates_returns200() throws Exception {
        when(salesEventMapper.toDomain(any())).thenAnswer(inv -> sampleTransaction());
        when(salesEventPort.ingest(any())).thenReturn(new IngestionResult.Duplicate(UUID.randomUUID()));

        MockHttpServletRequest req  = firehoseRequest(VALID_KEY, buildBatch(1));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void accessKeyMismatch_returns403() throws Exception {
        MockHttpServletRequest req  = firehoseRequest("wrong-key", buildBatch(1));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(403);
        verifyNoInteractions(salesEventPort);
    }

    @Test
    void emptyExpectedAccessKey_skipsValidation_processes() throws Exception {
        filter = new FirehoseBatchFilter(salesEventPort, salesEventMapper, objectMapper, "");
        when(salesEventMapper.toDomain(any())).thenAnswer(inv -> sampleTransaction());
        when(salesEventPort.ingest(any())).thenReturn(new IngestionResult.Accepted(UUID.randomUUID()));

        MockHttpServletRequest req  = firehoseRequest("any-key", buildBatch(1));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(200);
        verify(salesEventPort, times(1)).ingest(any());
    }

    @Test
    void response_containsRequestIdAndTimestamp() throws Exception {
        when(salesEventMapper.toDomain(any())).thenAnswer(inv -> sampleTransaction());
        when(salesEventPort.ingest(any())).thenReturn(new IngestionResult.Accepted(UUID.randomUUID()));

        MockHttpServletRequest req  = firehoseRequest(VALID_KEY, buildBatch(1));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        String body = resp.getContentAsString();
        assertThat(body).contains(REQUEST_ID);
        assertThat(body).contains("timestamp");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private MockHttpServletRequest firehoseRequest(String accessKey, String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", INGEST_PATH);
        req.addHeader("X-Amz-Firehose-Request-Id", REQUEST_ID);
        req.addHeader("X-Amz-Firehose-Access-Key", accessKey);
        req.setContentType("application/json");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        return req;
    }

    private String buildBatch(int count) {
        StringBuilder records = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) records.append(",");
            records.append("{\"data\":\"").append(encodeRecord(validSalesEventJson())).append("\"}");
        }
        records.append("]");
        return batchJson(REQUEST_ID, records.toString());
    }

    private String batchJson(String requestId, String recordsJson) {
        return "{\"requestId\":\"" + requestId + "\",\"timestamp\":1748600000000,\"records\":" + recordsJson + "}";
    }

    private String encodeRecord(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private String validSalesEventJson() {
        return "{\"transactionId\":\"" + UUID.randomUUID() + "\"," +
                "\"storeId\":\"STORE-001\",\"skuId\":\"SKU-BEV-001\",\"dcId\":\"DC-LONDON\"," +
                "\"quantity\":30,\"unitPrice\":8.50,\"channel\":\"POS\"," +
                "\"eventTimestamp\":\"2026-05-15T14:23:00Z\"}";
    }

    private SalesTransaction sampleTransaction() {
        return new SalesTransaction(
                UUID.randomUUID(), "STORE-001", "SKU-BEV-001", "DC-LONDON",
                30, BigDecimal.valueOf(8.50), SalesTransaction.Channel.POS,
                Instant.parse("2026-05-15T14:23:00Z")
        );
    }
}
