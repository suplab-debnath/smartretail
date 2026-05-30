package com.smartretail.lambda.mltrigger;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client that registers a new forecast run with DFS.
 * Implements ForecastRunRegistrar so MlTriggerHandler depends on the interface, not this class.
 *
 * Endpoint: POST {DFS_ENDPOINT}/v1/forecast/runs
 * Expected response: 201 Created with { runId, status, triggeredBy }.
 */
public class DfsRunClient implements ForecastRunRegistrar {

    /**
     * Thin result type so HttpSender avoids returning java.net.http.HttpResponse
     * (a sealed JDK interface unmockable by Byte Buddy on Java 25+).
     */
    record HttpResult(int statusCode, String body) {}

    /** Functional interface wrapping the HTTP send so tests can inject a lambda. */
    @FunctionalInterface
    interface HttpSender {
        HttpResult send(HttpRequest request) throws Exception;
    }

    private static final String RUNS_PATH = "/v1/forecast/runs";
    private static final int TIMEOUT_SECONDS = 30;

    private final HttpSender sender;
    private final ObjectMapper objectMapper;
    private final String dfsEndpoint;

    /** Production constructor — builds a real HttpClient internally. */
    public DfsRunClient(String dfsEndpoint) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
        this.sender = req -> {
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return new HttpResult(r.statusCode(), r.body());
        };
        this.dfsEndpoint = dfsEndpoint;
        this.objectMapper = new ObjectMapper();
    }

    /** Test constructor — accepts injected sender. */
    DfsRunClient(String dfsEndpoint, HttpSender sender) {
        this.dfsEndpoint = dfsEndpoint;
        this.sender = sender;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public UUID registerRun(String triggeredBy, LambdaLogger logger) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("triggeredBy", triggeredBy));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(dfsEndpoint + RUNS_PATH))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();

            HttpResult result = sender.send(request);

            if (result.statusCode() != 201) {
                throw new RuntimeException(
                        "DFS returned status " + result.statusCode() + " registering run: " + result.body());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = objectMapper.readValue(result.body(), Map.class);
            UUID runId = UUID.fromString((String) responseMap.get("runId"));
            logger.log("DFS run registered: runId=" + runId + " triggeredBy=" + triggeredBy);
            return runId;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to register forecast run in DFS", e);
        }
    }
}
