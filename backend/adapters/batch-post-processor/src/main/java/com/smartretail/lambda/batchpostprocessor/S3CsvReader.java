package com.smartretail.lambda.batchpostprocessor;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads a SageMaker transform output CSV from S3 and parses it into ForecastRowPayload list.
 *
 * CSV format (no header): sku_id,dc_id,forecast_date,horizon_days,p10,p50,p90
 * Empty lines and lines starting with '#' are silently skipped.
 * Malformed rows are logged and skipped — processing continues.
 */
public class S3CsvReader {

    private static final int EXPECTED_COLUMNS = 7;

    private final S3Client s3;

    public S3CsvReader(S3Client s3) {
        this.s3 = s3;
    }

    /**
     * Downloads and parses a single S3 CSV object.
     *
     * @return parsed rows; never null, may be empty
     * @throws RuntimeException on S3 GetObject failure
     */
    public List<ForecastRowPayload> readRows(String bucket, String key, LambdaLogger logger) {
        List<ForecastRowPayload> rows = new ArrayList<>();
        int lineNumber = 0;
        int skipped = 0;

        try (var stream = s3.getObject(GetObjectRequest.builder()
                .bucket(bucket).key(key).build());
             var reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] cols = line.split(",", -1);
                if (cols.length != EXPECTED_COLUMNS) {
                    logger.log("WARN S3CsvReader: skipping malformed line " + lineNumber
                            + " in " + key + ": expected " + EXPECTED_COLUMNS
                            + " columns, got " + cols.length);
                    skipped++;
                    continue;
                }

                try {
                    rows.add(new ForecastRowPayload(
                            cols[0].strip(),
                            cols[1].strip(),
                            LocalDate.parse(cols[2].strip()),
                            Integer.parseInt(cols[3].strip()),
                            Integer.parseInt(cols[4].strip()),
                            Integer.parseInt(cols[5].strip()),
                            Integer.parseInt(cols[6].strip())
                    ));
                } catch (Exception e) {
                    logger.log("WARN S3CsvReader: skipping invalid row at line " + lineNumber
                            + " in " + key + ": " + e.getMessage());
                    skipped++;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read S3 object s3://" + bucket + "/" + key, e);
        }

        logger.log("S3CsvReader: parsed " + rows.size() + " rows, skipped " + skipped
                + " from " + key);
        return rows;
    }

    /**
     * Deletes an S3 object. Best-effort: exceptions are logged as WARN and swallowed
     * so that a delete failure does not fail the Lambda invocation.
     */
    public void deleteObject(String bucket, String key, LambdaLogger logger) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            logger.log("Deleted S3 object after successful DFS ingest: s3://" + bucket + "/" + key);
        } catch (Exception e) {
            logger.log("WARN Failed to delete S3 object s3://" + bucket + "/" + key
                    + " — lifecycle rule will expire it. Reason: " + e.getMessage());
        }
    }
}
