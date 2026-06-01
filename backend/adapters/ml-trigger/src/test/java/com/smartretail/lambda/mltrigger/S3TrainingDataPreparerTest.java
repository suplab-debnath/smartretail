package com.smartretail.lambda.mltrigger;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3TrainingDataPreparerTest {

    private static final String EVENTS_BUCKET    = "test-events-bucket";
    private static final String SAGEMAKER_BUCKET = "test-sagemaker-bucket";
    private static final ObjectMapper MAPPER     = new ObjectMapper();

    @Mock private S3Client       s3;
    @Mock private LambdaLogger   logger;

    private S3TrainingDataPreparer preparer;

    @BeforeEach
    void setUp() {
        preparer = new S3TrainingDataPreparer(s3, EVENTS_BUCKET, SAGEMAKER_BUCKET);
    }

    @Test
    void prepare_uploadsThreeFiles_forValidEvents() throws Exception {
        UUID runId = UUID.randomUUID();

        // Two POS events: different SKUs, different stores
        String events = posEvent("SKU-BEV-001", "STORE-001", 5, "2025-06-01T10:00:00Z") + "\n"
                      + posEvent("SKU-BEV-001", "STORE-001", 3, "2025-06-02T11:00:00Z") + "\n"
                      + posEvent("SKU-SNK-001", "STORE-002", 7, "2025-06-01T09:00:00Z") + "\n";

        stubS3List(List.of("firehose/2025/06/01/file1", "firehose/2025/06/02/file1"));
        stubS3Get("firehose/2025/06/01/file1", events);
        stubS3Get("firehose/2025/06/02/file1", "");
        stubS3Put();

        preparer.prepare(runId, logger);

        // Should upload train.jsonl, test.jsonl, and predict.jsonl
        ArgumentCaptor<PutObjectRequest> keyCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, times(3)).putObject(keyCaptor.capture(), any(RequestBody.class));

        List<String> uploadedKeys = keyCaptor.getAllValues().stream()
                .map(PutObjectRequest::key)
                .toList();

        assertThat(uploadedKeys).containsExactlyInAnyOrder(
                "sagemaker/training/train.jsonl",
                "sagemaker/training/test.jsonl",
                "sagemaker/transform-input/" + runId + "/predict.jsonl"
        );
    }

    @Test
    void prepare_trainJsonl_containsCorrectSeriesForKnownSku() throws Exception {
        UUID runId = UUID.randomUUID();

        String events = posEvent("SKU-BEV-001", "STORE-001", 10, "2025-06-01T00:00:00Z") + "\n"
                      + posEvent("SKU-BEV-001", "STORE-001",  5, "2025-06-01T12:00:00Z") + "\n"; // same day, aggregated

        stubS3List(List.of("firehose/2025/06/01/f1"));
        stubS3Get("firehose/2025/06/01/f1", events);

        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        ArgumentCaptor<PutObjectRequest> keyCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        stubS3Put();

        preparer.prepare(runId, logger);

        verify(s3, times(3)).putObject(keyCaptor.capture(), bodyCaptor.capture());

        // Find train.jsonl upload
        int trainIdx = keyCaptor.getAllValues().indexOf(
                keyCaptor.getAllValues().stream()
                        .filter(r -> r.key().equals("sagemaker/training/train.jsonl"))
                        .findFirst().orElseThrow());

        byte[] trainBytes = bodyCaptor.getAllValues().get(trainIdx).contentStreamProvider()
                .newStream().readAllBytes();
        String trainContent = new String(trainBytes, StandardCharsets.UTF_8);
        String[] lines = trainContent.strip().split("\n");

        // 20 SKUs × 3 DCs = 60 lines
        assertThat(lines).hasSize(60);

        // Find the SKU-BEV-001 / DC-LONDON series (first in order)
        JsonNode bevLondon = MAPPER.readTree(lines[0]);
        assertThat(bevLondon.path("item_id").asText()).isEqualTo("SKU-BEV-001_DC-LONDON");
        assertThat(bevLondon.path("cat").get(0).asInt()).isEqualTo(0); // SKU index 0
        assertThat(bevLondon.path("cat").get(1).asInt()).isEqualTo(0); // DC-LONDON index 0
        assertThat(bevLondon.path("start").asText()).isNotEmpty();

        // The target array should contain the aggregated value (10+5=15) on 2025-06-01
        JsonNode target = bevLondon.path("target");
        assertThat(target.isArray()).isTrue();
        // At least one non-zero value (15 units on 2025-06-01)
        boolean hasValue = false;
        for (JsonNode v : target) {
            if (v.asInt() == 15) { hasValue = true; break; }
        }
        assertThat(hasValue).as("target array should contain aggregated value 15").isTrue();
    }

    @Test
    void prepare_skipsUnknownStoreIds() throws Exception {
        UUID runId = UUID.randomUUID();

        String events = posEvent("SKU-BEV-001", "STORE-UNKNOWN", 5, "2025-06-01T00:00:00Z") + "\n";

        stubS3List(List.of("firehose/2025/06/01/f1"));
        stubS3Get("firehose/2025/06/01/f1", events);
        stubS3Put();

        preparer.prepare(runId, logger);

        // Files still uploaded — unknown stores produce zero-filled series
        verify(s3, times(3)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void prepare_skipsMalformedLines() throws Exception {
        UUID runId = UUID.randomUUID();
        String events = "not-json\n" + posEvent("SKU-BEV-001", "STORE-001", 5, "2025-06-01T10:00:00Z") + "\n";

        stubS3List(List.of("firehose/2025/06/01/f1"));
        stubS3Get("firehose/2025/06/01/f1", events);
        stubS3Put();

        // Should not throw
        preparer.prepare(runId, logger);
        verify(s3, times(3)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void prepare_predictJsonl_usesRunIdInKey() throws Exception {
        UUID runId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        stubS3List(List.of());
        stubS3Put();

        preparer.prepare(runId, logger);

        ArgumentCaptor<PutObjectRequest> keyCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, times(3)).putObject(keyCaptor.capture(), any(RequestBody.class));

        assertThat(keyCaptor.getAllValues())
                .extracting(PutObjectRequest::key)
                .contains("sagemaker/transform-input/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/predict.jsonl");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String posEvent(String skuId, String storeId, int quantity, String soldAt) {
        return String.format(
                "{\"transactionId\":\"tx-%s\",\"skuId\":\"%s\",\"storeId\":\"%s\",\"quantity\":%d,\"soldAt\":\"%s\",\"channel\":\"POS\"}",
                UUID.randomUUID(), skuId, storeId, quantity, soldAt);
    }

    @SuppressWarnings("unchecked")
    private void stubS3List(List<String> keys) {
        ListObjectsV2Response page = ListObjectsV2Response.builder()
                .contents(keys.stream()
                        .map(k -> S3Object.builder().key(k).build())
                        .toList())
                .isTruncated(false)
                .build();

        ListObjectsV2Iterable iterable = mock(ListObjectsV2Iterable.class);
        when(iterable.iterator()).thenReturn(List.of(page).iterator());
        when(s3.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(iterable);
    }

    @SuppressWarnings("unchecked")
    private void stubS3Get(String key, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ResponseInputStream<GetObjectResponse> stream =
                new ResponseInputStream<>(GetObjectResponse.builder().build(),
                        new ByteArrayInputStream(bytes));
        when(s3.getObject(argThat((Consumer<GetObjectRequest.Builder> b) -> {
            if (b == null) return false;
            GetObjectRequest.Builder rb = GetObjectRequest.builder();
            b.accept(rb);
            return rb.build().key().equals(key);
        }))).thenReturn(stream);
    }

    private void stubS3Put() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
    }
}
