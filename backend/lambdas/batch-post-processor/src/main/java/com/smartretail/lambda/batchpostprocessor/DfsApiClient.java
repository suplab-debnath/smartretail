package com.smartretail.lambda.batchpostprocessor;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client that POSTs parsed forecast rows to the DFS ingest endpoint.
 * Endpoint: POST {DFS_ENDPOINT}/v1/forecast/runs/{runId}/results
 * Expected response: 201 Created.
 * Mirrors the SisApiClient pattern from kinesis-consumer.
 */
public class DfsApiClient {

    private static final String INGEST_PATH_TEMPLATE = "/v1/forecast/runs/%s/results";
    private static final int TIMEOUT_SECONDS = 30;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String dfsEndpoint;

    public DfsApiClient(String dfsEndpoint) {
        this.dfsEndpoint = dfsEndpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Posts all rows for a given run to DFS.
     *
     * @return HTTP status code (201 = success)
     * @throws RuntimeException on non-201 response or I/O failure — triggers Lambda retry
     */
    public int postResults(UUID runId, List<ForecastRowPayload> rows, LambdaLogger logger) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("rows", rows));
            String path = String.format(INGEST_PATH_TEMPLATE, runId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(dfsEndpoint + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 201) {
                logger.log("DFS ingest accepted: runId=" + runId + " rows=" + rows.size());
                return status;
            }

            logger.log("DFS returned status " + status + " for runId=" + runId
                    + " body=" + response.body());
            throw new RuntimeException("DFS ingest failed with status " + status
                    + " for runId=" + runId);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to POST to DFS for runId=" + runId, e);
        }
    }
}
