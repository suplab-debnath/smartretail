package com.smartretail.lambda.batchpostprocessor;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import java.util.List;

/**
 * Downloads a SageMaker transform output CSV from S3 and parses it into ForecastRowPayload list.
 */
public interface S3CsvReader {

    /**
     * Downloads and parses a single S3 CSV object.
     *
     * @return parsed rows; never null, may be empty
     * @throws RuntimeException on S3 GetObject failure
     */
    List<ForecastRowPayload> readRows(String bucket, String key, LambdaLogger logger);
}
