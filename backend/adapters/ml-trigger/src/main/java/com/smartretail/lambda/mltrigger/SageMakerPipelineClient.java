package com.smartretail.lambda.mltrigger;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import software.amazon.awssdk.services.sagemaker.SageMakerClient;
import software.amazon.awssdk.services.sagemaker.model.Parameter;
import software.amazon.awssdk.services.sagemaker.model.StartPipelineExecutionRequest;
import software.amazon.awssdk.services.sagemaker.model.StartPipelineExecutionResponse;

import java.util.UUID;

/**
 * Calls SageMaker StartPipelineExecution, passing the DFS runId as a pipeline parameter.
 * Implements PipelineStarter so MlTriggerHandler depends on the interface, not this class.
 *
 * The SageMaker pipeline is pre-configured to write transform output to
 * sagemaker/output/{RunId}/part-*.csv, which the Batch Post-Processor Lambda picks up.
 */
public class SageMakerPipelineClient implements PipelineStarter {

    /** Functional interface so tests can inject a lambda without needing to mock SageMakerClient. */
    @FunctionalInterface
    interface SageMakerExecutor {
        StartPipelineExecutionResponse execute(StartPipelineExecutionRequest request);
    }

    private final SageMakerExecutor executor;
    private final String pipelineName;

    /** Production constructor — delegates to real SDK client. */
    public SageMakerPipelineClient(SageMakerClient sageMaker, String pipelineName) {
        this(sageMaker::startPipelineExecution, pipelineName);
    }

    /** Test constructor — accepts injected executor. */
    SageMakerPipelineClient(SageMakerExecutor executor, String pipelineName) {
        this.executor = executor;
        this.pipelineName = pipelineName;
    }

    @Override
    public String startExecution(UUID runId, LambdaLogger logger) {
        StartPipelineExecutionResponse response = executor.execute(
                StartPipelineExecutionRequest.builder()
                        .pipelineName(pipelineName)
                        .pipelineExecutionDisplayName("smartretail-run-" + runId.toString().substring(0, 8))
                        .pipelineParameters(
                                Parameter.builder().name("RunId").value(runId.toString()).build()
                        )
                        .build()
        );
        String arn = response.pipelineExecutionArn();
        logger.log("SageMaker pipeline execution: arn=" + arn + " runId=" + runId);
        return arn;
    }
}
