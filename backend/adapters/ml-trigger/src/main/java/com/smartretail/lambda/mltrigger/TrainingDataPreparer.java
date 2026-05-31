package com.smartretail.lambda.mltrigger;

import com.amazonaws.services.lambda.runtime.LambdaLogger;

import java.util.UUID;

/**
 * Reads raw POS events from S3 (Firehose AllData backup), aggregates daily
 * demand per SKU × DC, and writes DeepAR JSON Lines training files to the
 * SageMaker bucket before each pipeline execution.
 */
public interface TrainingDataPreparer {
    void prepare(UUID runId, LambdaLogger logger);
}
