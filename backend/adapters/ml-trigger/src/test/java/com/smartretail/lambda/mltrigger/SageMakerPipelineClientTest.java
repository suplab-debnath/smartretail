package com.smartretail.lambda.mltrigger;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sagemaker.model.StartPipelineExecutionRequest;
import software.amazon.awssdk.services.sagemaker.model.StartPipelineExecutionResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SageMakerPipelineClientTest {

    @Mock private SageMakerPipelineClient.SageMakerExecutor executor;
    @Mock private LambdaLogger logger;

    private SageMakerPipelineClient client;

    @BeforeEach
    void setUp() {
        client = new SageMakerPipelineClient(executor, "smartretail-demand-forecast-dev");
    }

    @Test
    void startExecution_returnsExecutionArn() {
        UUID runId = UUID.randomUUID();
        String expectedArn = "arn:aws:sagemaker:us-east-1:123:pipeline/smartretail-demand-forecast-dev/execution/abc";
        when(executor.execute(any())).thenReturn(
                StartPipelineExecutionResponse.builder().pipelineExecutionArn(expectedArn).build());

        String arn = client.startExecution(runId, logger);

        assertThat(arn).isEqualTo(expectedArn);
    }

    @Test
    void startExecution_passesRunIdAsParameter() {
        UUID runId = UUID.randomUUID();
        when(executor.execute(any())).thenReturn(
                StartPipelineExecutionResponse.builder().pipelineExecutionArn("arn:test").build());

        client.startExecution(runId, logger);

        ArgumentCaptor<StartPipelineExecutionRequest> captor =
                ArgumentCaptor.forClass(StartPipelineExecutionRequest.class);
        verify(executor).execute(captor.capture());

        StartPipelineExecutionRequest req = captor.getValue();
        assertThat(req.pipelineName()).isEqualTo("smartretail-demand-forecast-dev");
        assertThat(req.pipelineParameters()).hasSize(1);
        assertThat(req.pipelineParameters().get(0).name()).isEqualTo("RunId");
        assertThat(req.pipelineParameters().get(0).value()).isEqualTo(runId.toString());
    }

    @Test
    void startExecution_displayNameContainsRunIdPrefix() {
        UUID runId = UUID.randomUUID();
        when(executor.execute(any())).thenReturn(
                StartPipelineExecutionResponse.builder().pipelineExecutionArn("arn:test").build());

        client.startExecution(runId, logger);

        ArgumentCaptor<StartPipelineExecutionRequest> captor =
                ArgumentCaptor.forClass(StartPipelineExecutionRequest.class);
        verify(executor).execute(captor.capture());
        assertThat(captor.getValue().pipelineExecutionDisplayName())
                .isEqualTo("smartretail-run-" + runId.toString().substring(0, 8));
    }

    @Test
    void startExecution_propagatesExecutorException() {
        UUID runId = UUID.randomUUID();
        when(executor.execute(any())).thenThrow(new RuntimeException("Pipeline not found"));

        assertThatThrownBy(() -> client.startExecution(runId, logger))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Pipeline not found");
    }
}
