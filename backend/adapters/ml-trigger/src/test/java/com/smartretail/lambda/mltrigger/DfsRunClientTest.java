package com.smartretail.lambda.mltrigger;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpRequest;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DfsRunClientTest {

    // Mock the inner functional interface — always mockable (it's an interface)
    @Mock private DfsRunClient.HttpSender sender;
    @Mock private LambdaLogger logger;

    private DfsRunClient client;

    @BeforeEach
    void setUp() {
        client = new DfsRunClient("http://dfs:8084", sender);
    }

    @Test
    void registerRun_returnsRunIdOnSuccess() throws Exception {
        UUID runId = UUID.randomUUID();
        String responseBody = "{\"runId\":\"" + runId + "\",\"status\":\"TRIGGERED\",\"triggeredBy\":\"SCHEDULED\"}";

        // HttpResult is a plain record — no mocking needed
        when(sender.send(any())).thenReturn(new DfsRunClient.HttpResult(201, responseBody));

        UUID result = client.registerRun("SCHEDULED", logger);

        assertThat(result).isEqualTo(runId);
    }

    @Test
    void registerRun_throwsOnNon201Response() throws Exception {
        when(sender.send(any())).thenReturn(new DfsRunClient.HttpResult(500, "Internal Server Error"));

        assertThatThrownBy(() -> client.registerRun("SCHEDULED", logger))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("500");
    }

    @Test
    void registerRun_throwsOnNetworkError() throws Exception {
        when(sender.send(any())).thenThrow(new java.io.IOException("Connection refused"));

        assertThatThrownBy(() -> client.registerRun("SCHEDULED", logger))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to register forecast run");
    }

    @Test
    void registerRun_sendsPostToCorrectPath() throws Exception {
        UUID runId = UUID.randomUUID();
        String responseBody = "{\"runId\":\"" + runId + "\",\"status\":\"TRIGGERED\",\"triggeredBy\":\"MANUAL\"}";

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        when(sender.send(requestCaptor.capture())).thenReturn(new DfsRunClient.HttpResult(201, responseBody));

        client.registerRun("MANUAL", logger);

        HttpRequest captured = requestCaptor.getValue();
        assertThat(captured.uri().toString()).isEqualTo("http://dfs:8084/v1/forecast/runs");
        assertThat(captured.method()).isEqualTo("POST");
    }
}
