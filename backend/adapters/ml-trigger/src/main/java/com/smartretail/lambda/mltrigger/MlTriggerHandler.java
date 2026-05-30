package com.smartretail.lambda.mltrigger;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import software.amazon.awssdk.services.sagemaker.SageMakerClient;

import java.util.UUID;

/**
 * Lambda handler triggered by an EventBridge scheduled rule (daily at 02:00 UTC).
 *
 * Sequence:
 *   1. POST /v1/forecast/runs to DFS → receives runId
 *   2. StartPipelineExecution on SageMaker, passing RunId as pipeline parameter
 *
 * SageMaker pipeline writes transform output to:
 *   sagemaker/output/{runId}/part-*.csv
 * which triggers the Batch Post-Processor Lambda to ingest results into DFS.
 *
 * No domain logic — pure infrastructure adapter.
 */
public class MlTriggerHandler implements RequestHandler<ScheduledEvent, Void> {

    private final ForecastRunRegistrar registrar;
    private final PipelineStarter starter;

    /** Production constructor — reads env vars, builds clients. */
    public MlTriggerHandler() {
        this.registrar = new DfsRunClient(requireEnv("DFS_ENDPOINT"));
        this.starter   = new SageMakerPipelineClient(
                SageMakerClient.create(), requireEnv("SAGEMAKER_PIPELINE_NAME"));
    }

    /** Test constructor — accepts injected collaborators via interfaces. */
    MlTriggerHandler(ForecastRunRegistrar registrar, PipelineStarter starter) {
        this.registrar = registrar;
        this.starter   = starter;
    }

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("ML Trigger fired: source=" + event.getSource());

        UUID runId = registrar.registerRun("SCHEDULED", logger);
        logger.log("Registered forecast run: runId=" + runId);

        String executionArn = starter.startExecution(runId, logger);
        logger.log("SageMaker pipeline execution started: arn=" + executionArn);

        return null;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable not set: " + name);
        }
        return value;
    }
}
