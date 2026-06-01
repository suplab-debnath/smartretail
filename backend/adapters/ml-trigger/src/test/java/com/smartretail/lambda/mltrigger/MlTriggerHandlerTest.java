package com.smartretail.lambda.mltrigger;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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

    @Mock private ForecastRunRegistrar registrar;
    @Mock private TrainingDataPreparer trainingDataPreparer;
    @Mock private PipelineStarter      starter;
    @Mock private Context              context;
    @Mock private LambdaLogger         logger;

    private MlTriggerHandler handler;
    private ScheduledEvent    event;

    @BeforeEach
    void setUp() {
        when(context.getLogger()).thenReturn(logger);
        handler = new MlTriggerHandler(registrar, trainingDataPreparer, starter);
        event   = new ScheduledEvent();
        event.setSource("aws.events");
    }

    @Test
    void handleRequest_executesStepsInOrder() {
        UUID runId = UUID.randomUUID();
        when(registrar.registerRun(eq("SCHEDULED"), any())).thenReturn(runId);
        when(starter.startExecution(eq(runId), any())).thenReturn("arn:test");

        handler.handleRequest(event, context);

        InOrder order = inOrder(registrar, trainingDataPreparer, starter);
        order.verify(registrar).registerRun(eq("SCHEDULED"), any());
        order.verify(trainingDataPreparer).prepare(eq(runId), any());
        order.verify(starter).startExecution(eq(runId), any());
    }

    @Test
    void handleRequest_propagatesExceptionWhenRegistrarFails() {
        when(registrar.registerRun(any(), any()))
                .thenThrow(new RuntimeException("DFS unavailable"));

        assertThrows(RuntimeException.class, () -> handler.handleRequest(event, context));
        verifyNoInteractions(trainingDataPreparer, starter);
    }

    @Test
    void handleRequest_propagatesExceptionWhenPreparerFails() {
        UUID runId = UUID.randomUUID();
        when(registrar.registerRun(any(), any())).thenReturn(runId);
        doThrow(new RuntimeException("S3 error")).when(trainingDataPreparer).prepare(any(), any());

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
