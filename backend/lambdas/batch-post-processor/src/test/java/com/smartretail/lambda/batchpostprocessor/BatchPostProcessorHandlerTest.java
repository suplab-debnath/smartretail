package com.smartretail.lambda.batchpostprocessor;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchPostProcessorHandlerTest {

    @Mock private S3CsvReader s3CsvReader;
    @Mock private DfsApiClient dfsApiClient;
    @Mock private Context context;
    @Mock private LambdaLogger logger;

    private BatchPostProcessorHandler handler;

    @BeforeEach
    void setUp() {
        when(context.getLogger()).thenReturn(logger);
        handler = new BatchPostProcessorHandler(s3CsvReader, dfsApiClient);
    }

    @Test
    void shouldExtractRunIdAndPostToDfs() {
        UUID runId = UUID.randomUUID();
        String key = "sagemaker/output/" + runId + "/part-0.csv";
        S3Event event = buildS3Event("smartretail-sagemaker-dev", key);

        List<ForecastRowPayload> rows = List.of(
                new ForecastRowPayload("SKU-001", "DC-LONDON", LocalDate.of(2026, 6, 1), 30, 80, 100, 130)
        );
        when(s3CsvReader.readRows(any(), any(), any())).thenReturn(rows);
        when(dfsApiClient.postResults(any(), any(), any())).thenReturn(201);

        handler.handleRequest(event, context);

        verify(s3CsvReader).readRows(eq("smartretail-sagemaker-dev"), eq(key), eq(logger));
        verify(dfsApiClient).postResults(eq(runId), eq(rows), eq(logger));
    }

    @Test
    void shouldSkipRecordWithNonMatchingKey() {
        S3Event event = buildS3Event("some-bucket", "unrelated/path/file.csv");

        handler.handleRequest(event, context);

        verify(s3CsvReader, never()).readRows(any(), any(), any());
        verify(dfsApiClient, never()).postResults(any(), any(), any());
    }

    @Test
    void shouldSkipRecordWithEmptyCsv() {
        UUID runId = UUID.randomUUID();
        S3Event event = buildS3Event("bucket", "sagemaker/output/" + runId + "/part-0.csv");
        when(s3CsvReader.readRows(any(), any(), any())).thenReturn(List.of());

        handler.handleRequest(event, context);

        verify(dfsApiClient, never()).postResults(any(), any(), any());
    }

    @Test
    void shouldPropagateExceptionWhenDfsFails() {
        UUID runId = UUID.randomUUID();
        S3Event event = buildS3Event("bucket", "sagemaker/output/" + runId + "/part-0.csv");
        List<ForecastRowPayload> rows = List.of(
                new ForecastRowPayload("SKU-001", "DC-LONDON", LocalDate.of(2026, 6, 1), 30, 80, 100, 130)
        );
        when(s3CsvReader.readRows(any(), any(), any())).thenReturn(rows);
        when(dfsApiClient.postResults(any(), any(), any()))
                .thenThrow(new RuntimeException("DFS unavailable"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.handleRequest(event, context));
        assertTrue(ex.getMessage().contains("DFS unavailable"));
    }

    private S3Event buildS3Event(String bucket, String key) {
        var s3Entity = new S3EventNotification.S3Entity(
                null,
                new S3EventNotification.S3BucketEntity(bucket, null, null),
                new S3EventNotification.S3ObjectEntity(key, null, null, null, null),
                null);
        var record = new S3EventNotification.S3EventNotificationRecord(
                null, null, null, null, null, null, null, s3Entity, null);
        return new S3Event(List.of(record));
    }
}
