package com.smartretail.lambda.kinesis;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class SisApiClient {

    private static final String INGEST_PATH = "/v1/ingest/events";
    private static final int HTTP_TIMEOUT_SECONDS = 10;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String sisEndpoint;

    public SisApiClient(String sisEndpoint) {
        this.sisEndpoint = sisEndpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Posts a POS event to the SIS ingest endpoint.
     *
     * @return HTTP status code (202 = accepted, 409 = duplicate)
     * @throws RuntimeException on non-2xx/409 response or IO failure
     */
    public int postEvent(PosEventPayload payload, LambdaLogger logger) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sisEndpoint + INGEST_PATH))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 202 || status == 409) {
                return status;
            }
            logger.log("SIS returned unexpected status " + status + " for transactionId=" + payload.transactionId());
            throw new RuntimeException("SIS returned status " + status + " — triggering Kinesis retry");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call SIS endpoint for transactionId=" + payload.transactionId(), e);
        }
    }
}
