package com.smartretail.lambda.mltrigger;

import com.amazonaws.services.lambda.runtime.LambdaLogger;

import java.util.UUID;

/** Port: starts the SageMaker pipeline execution for a given forecast runId. */
interface PipelineStarter {
    String startExecution(UUID runId, LambdaLogger logger);
}
