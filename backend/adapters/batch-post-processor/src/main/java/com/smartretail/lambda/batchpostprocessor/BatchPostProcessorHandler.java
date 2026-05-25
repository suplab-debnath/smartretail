package com.smartretail.lambda.batchpostprocessor;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lambda handler triggered by S3 ObjectCreated events from the SageMaker
 * batch transform output bucket.
 *
 * S3 key convention: sagemaker/output/{run_id}/part-*.csv
 * The {run_id} UUID is extracted and passed to DFS via HTTP POST.
 *
 * No domain logic — pure infrastructure adapter.
 */
public class BatchPostProcessorHandler implements RequestHandler<S3Event, Void> {

    /** Matches sagemaker/output/<UUID>/anything — group 1 is the UUID. */
    private static final Pattern RUN_ID_PATTERN =
            Pattern.compile("^sagemaker/output/([0-9a-fA-F-]{36})/.*$");

    private final S3CsvReader s3CsvReader;
    private final DfsApiClient dfsApiClient;

    /** Production constructor — reads env vars, builds AWS clients. */
    public BatchPostProcessorHandler() {
        this.s3CsvReader  = new S3CsvReader(S3Client.create());
        this.dfsApiClient = new DfsApiClient(requireEnv("DFS_ENDPOINT"));
    }

    /** Test constructor — accepts injected collaborators. */
    BatchPostProcessorHandler(S3CsvReader s3CsvReader, DfsApiClient dfsApiClient) {
        this.s3CsvReader  = s3CsvReader;
        this.dfsApiClient = dfsApiClient;
    }

    @Override
    public Void handleRequest(S3Event event, Context context) {
        LambdaLogger logger = context.getLogger();

        for (S3EventNotificationRecord record : event.getRecords()) {
            String bucket = record.getS3().getBucket().getName();
            String key    = record.getS3().getObject().getUrlDecodedKey();

            logger.log("Processing S3 event: bucket=" + bucket + " key=" + key);

            UUID runId = extractRunId(key, logger);
            if (runId == null) {
                logger.log("WARN Skipping key with unrecognised pattern: " + key);
                continue;
            }

            List<ForecastRowPayload> rows = s3CsvReader.readRows(bucket, key, logger);
            if (rows.isEmpty()) {
                logger.log("WARN No parseable rows in " + key + " — skipping DFS call");
                continue;
            }

            // Throws RuntimeException on non-201 → Lambda retries the event
            dfsApiClient.postResults(runId, rows, logger);

            logger.log("Completed: runId=" + runId + " rows=" + rows.size());
        }

        return null;
    }

    private UUID extractRunId(String key, LambdaLogger logger) {
        Matcher m = RUN_ID_PATTERN.matcher(key);
        if (!m.matches()) return null;
        try {
            return UUID.fromString(m.group(1));
        } catch (IllegalArgumentException e) {
            logger.log("WARN Invalid UUID in key: " + key);
            return null;
        }
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable not set: " + name);
        }
        return value;
    }
}
