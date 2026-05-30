package com.smartretail.lambda.mltrigger;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MlTriggerHandlerTest {

    // Mock the interfaces — Mockito can always mock interfaces regardless of Java version
    @Mock private ForecastRunRegistrar registrar;
    @Mock private PipelineStarter starter;
    @Mock private Context context;
    @Mock private LambdaLogger logger;

    private MlTriggerHandler handler;
    private ScheduledEvent event;

    @BeforeEach
    void setUp() {
        when(context.getLogger()).thenReturn(logger);
        handler = new MlTriggerHandler(registrar, starter);
        event = new ScheduledEvent();
        event.setSource("aws.events");
    }

    @Test
    void handleRequest_registersRunThenStartsPipeline() {
        UUID runId = UUID.randomUUID();
        String executionArn = "arn:aws:sagemaker:us-east-1:123:pipeline/test/execution/abc";

        when(registrar.registerRun(eq("SCHEDULED"), any())).thenReturn(runId);
        when(starter.startExecution(eq(runId), any())).thenReturn(executionArn);

        handler.handleRequest(event, context);

        verify(registrar).registerRun(eq("SCHEDULED"), any());
        verify(starter).startExecution(eq(runId), any());
    }

    @Test
    void handleRequest_propagatesExceptionWhenRegistrarFails() {
        when(registrar.registerRun(any(), any()))
                .thenThrow(new RuntimeException("DFS unavailable"));

        assertThrows(RuntimeException.class, () -> handler.handleRequest(event, context));
        verifyNoInteractions(starter);
    }

    @Test
    void handleRequest_propagatesExceptionWhenStarterFails() {
        UUID runId = UUID.randomUUID();
        when(registrar.registerRun(any(), any())).thenReturn(runId);
        when(starter.startExecution(any(), any()))
                .thenThrow(new RuntimeException("SageMaker error"));

        assertThrows(RuntimeException.class, () -> handler.handleRequest(event, context));
    }

    @Test
    void handleRequest_returnsNull() {
        UUID runId = UUID.randomUUID();
        when(registrar.registerRun(any(), any())).thenReturn(runId);
        when(starter.startExecution(any(), any())).thenReturn("arn:test");

        assertNull(handler.handleRequest(event, context));
    }
}
