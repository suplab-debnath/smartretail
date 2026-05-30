package com.smartretail.sis.adapter.inbound.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartretail.sis.adapter.in.web.generated.model.SalesEventRequest;
import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.inbound.SalesEventPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

/**
 * Intercepts POST /v1/ingest/events when the X-Amz-Firehose-Request-Id header is present.
 * Parses the Firehose batch envelope, decodes each record, and calls the ingest port.
 * Always returns HTTP 200 (Firehose contract: non-2xx triggers full batch retry).
 * Falls through to Spring MVC for direct single-record POSTs (no Firehose header).
 */
@Component
public class FirehoseBatchFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirehoseBatchFilter.class);
    private static final String FIREHOSE_REQUEST_ID_HEADER = "X-Amz-Firehose-Request-Id";
    private static final String FIREHOSE_ACCESS_KEY_HEADER = "X-Amz-Firehose-Access-Key";
    private static final String INGEST_PATH = "/v1/ingest/events";

    private final SalesEventPort salesEventPort;
    private final SalesEventMapper salesEventMapper;
    private final ObjectMapper objectMapper;
    private final String expectedAccessKey;

    public FirehoseBatchFilter(
            SalesEventPort salesEventPort,
            SalesEventMapper salesEventMapper,
            ObjectMapper objectMapper,
            @Value("${smartretail.firehose.access-key:}") String expectedAccessKey) {
        this.salesEventPort = salesEventPort;
        this.salesEventMapper = salesEventMapper;
        this.objectMapper = objectMapper;
        this.expectedAccessKey = expectedAccessKey;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !INGEST_PATH.equals(request.getRequestURI())
                || request.getHeader(FIREHOSE_REQUEST_ID_HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(FIREHOSE_REQUEST_ID_HEADER);

        if (!expectedAccessKey.isEmpty()) {
            String providedKey = request.getHeader(FIREHOSE_ACCESS_KEY_HEADER);
            if (!expectedAccessKey.equals(providedKey)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }

        FirehoseBatch batch;
        try {
            batch = objectMapper.readValue(request.getInputStream(), FirehoseBatch.class);
        } catch (IOException e) {
            log.error("Failed to parse Firehose batch body: {}", e.getMessage());
            writeFirehoseResponse(response, requestId);
            return;
        }

        for (FirehoseBatch.Record record : batch.records()) {
            try {
                byte[] decoded = Base64.getDecoder().decode(record.data());
                SalesEventRequest req = objectMapper.readValue(decoded, SalesEventRequest.class);
                SalesTransaction transaction = salesEventMapper.toDomain(req);
                salesEventPort.ingest(transaction);
            } catch (Exception e) {
                log.warn("Skipping invalid Firehose record: {}", e.getMessage());
            }
        }

        writeFirehoseResponse(response, requestId);
    }

    private void writeFirehoseResponse(HttpServletResponse response, String requestId) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(),
                new FirehoseResponse(requestId, System.currentTimeMillis()));
    }

    record FirehoseBatch(String requestId, long timestamp, List<Record> records) {
        record Record(String data) {}
    }

    record FirehoseResponse(String requestId, long timestamp) {}
}
