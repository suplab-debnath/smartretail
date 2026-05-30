package com.smartretail.lambda.batchpostprocessor;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DfsApiClientTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    @Mock
    private LambdaLogger logger;

    private DfsApiClient dfsApiClient;
    private final String endpoint = "http://dfs.internal:8084";

    @BeforeEach
    void setUp() {
        dfsApiClient = new DfsApiClient(endpoint, httpClient);
    }

    @Test
    void shouldPostResultsSuccessfully() throws IOException, InterruptedException {
        UUID runId = UUID.randomUUID();
        List<ForecastRowPayload> rows = List.of(
                new ForecastRowPayload("SKU-001", "DC-1", LocalDate.now(), 30, 10, 20, 30)
        );

        when(httpResponse.statusCode()).thenReturn(201);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        int status = dfsApiClient.postResults(runId, rows, logger);

        assertEquals(201, status);
    }

    @Test
    void shouldThrowExceptionOnNon201Status() throws IOException, InterruptedException {
        UUID runId = UUID.randomUUID();
        List<ForecastRowPayload> rows = List.of(
                new ForecastRowPayload("SKU-001", "DC-1", LocalDate.now(), 30, 10, 20, 30)
        );

        when(httpResponse.statusCode()).thenReturn(400);
        when(httpResponse.body()).thenReturn("Bad Request details");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                dfsApiClient.postResults(runId, rows, logger)
        );

        assertTrue(ex.getMessage().contains("DFS ingest failed with status 400"));
    }

    @Test
    void shouldThrowExceptionOnIOException() throws IOException, InterruptedException {
        UUID runId = UUID.randomUUID();
        List<ForecastRowPayload> rows = List.of(
                new ForecastRowPayload("SKU-001", "DC-1", LocalDate.now(), 30, 10, 20, 30)
        );

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection timed out"));

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                dfsApiClient.postResults(runId, rows, logger)
        );

        assertTrue(ex.getMessage().contains("Failed to POST to DFS"));
        assertTrue(ex.getCause() instanceof IOException);
    }
}
