package com.smartretail.lambda.mltrigger;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads raw POS event JSON Lines written by Firehose (s3BackupMode=AllData)
 * to the events bucket, aggregates daily demand per SKU × DC over a rolling
 * LOOKBACK_DAYS window, then writes DeepAR-format JSON Lines to the SageMaker
 * bucket. Called by MlTriggerHandler on every daily invocation.
 *
 * Output paths:
 *   sagemaker/training/train.jsonl     — all days except last TEST_HOLDOUT
 *   sagemaker/training/test.jsonl      — all days (holdout evaluated internally by DeepAR)
 *   sagemaker/transform-input/{runId}/predict.jsonl — last CONTEXT_LENGTH days per series
 *
 * No domain logic. Pure infrastructure adapter.
 */
public class S3TrainingDataPreparer implements TrainingDataPreparer {

    // DeepAR parameters — must match create-sagemaker-pipeline.py
    private static final int PREDICTION_LENGTH = 30;
    private static final int CONTEXT_LENGTH    = 90;
    private static final int TEST_HOLDOUT      = 30;
    private static final int LOOKBACK_DAYS     = 400; // rolling window to keep Lambda runtime bounded

    // Categorical indices — must be stable across all runs
    private static final List<String> SKU_ORDER = List.of(
            "SKU-BEV-001", "SKU-BEV-002", "SKU-BEV-003", "SKU-BEV-004",
            "SKU-SNK-001", "SKU-SNK-002", "SKU-SNK-003", "SKU-SNK-004",
            "SKU-DRY-001", "SKU-DRY-002", "SKU-DRY-003", "SKU-DRY-004", "SKU-DRY-005",
            "SKU-CHL-001", "SKU-CHL-002", "SKU-CHL-003", "SKU-CHL-004", "SKU-CHL-005",
            "SKU-PRO-001", "SKU-PRO-002"
    );
    private static final List<String> DC_ORDER = List.of(
            "DC-LONDON", "DC-MANCHESTER", "DC-BIRMINGHAM"
    );
    private static final Map<String, String> STORE_TO_DC = Map.of(
            "STORE-001", "DC-LONDON",
            "STORE-002", "DC-MANCHESTER",
            "STORE-003", "DC-BIRMINGHAM"
    );

    private final S3Client s3;
    private final String eventsBucket;
    private final String sagemakerBucket;
    private final ObjectMapper mapper = new ObjectMapper();

    public S3TrainingDataPreparer(S3Client s3, String eventsBucket, String sagemakerBucket) {
        this.s3              = s3;
        this.eventsBucket    = eventsBucket;
        this.sagemakerBucket = sagemakerBucket;
    }

    @Override
    public void prepare(UUID runId, LambdaLogger logger) {
        LocalDate today   = LocalDate.now();
        LocalDate since   = today.minusDays(LOOKBACK_DAYS);
        LocalDate trainEnd = today.minusDays(TEST_HOLDOUT);

        logger.log("TrainingDataPreparer: reading events since=" + since + " from s3://" + eventsBucket + "/firehose/");

        // key → (skuId, dcId) → date → units
        Map<String, Map<LocalDate, Integer>> dailySales = aggregateSales(since, logger);

        logger.log("TrainingDataPreparer: aggregated " + dailySales.size() + " SKU×DC series");

        byte[] trainBytes   = buildJsonLines(dailySales, since, trainEnd);
        byte[] testBytes    = buildJsonLines(dailySales, since, today);
        byte[] predictBytes = buildPredictJsonLines(dailySales, today);

        put("sagemaker/training/train.jsonl",                                  trainBytes,   logger);
        put("sagemaker/training/test.jsonl",                                   testBytes,    logger);
        put("sagemaker/transform-input/" + runId + "/predict.jsonl", predictBytes, logger);
    }

    // ── S3 event aggregation ──────────────────────────────────────────────────

    private Map<String, Map<LocalDate, Integer>> aggregateSales(LocalDate since, LambdaLogger logger) {
        // key: "skuId|dcId"
        Map<String, Map<LocalDate, Integer>> totals = new HashMap<>();

        var paginator = s3.listObjectsV2Paginator(
                ListObjectsV2Request.builder()
                        .bucket(eventsBucket)
                        .prefix("firehose/")
                        .build()
        );

        int filesRead = 0;
        for (var page : paginator) {
            for (S3Object obj : page.contents()) {
                if (!isOnOrAfter(obj.key(), since)) continue;
                processFile(obj.key(), totals, logger);
                filesRead++;
            }
        }
        logger.log("TrainingDataPreparer: processed " + filesRead + " Firehose files");
        return totals;
    }

    /**
     * S3 key pattern: firehose/yyyy/MM/dd/...
     * Returns false if the key's date is before `since`.
     */
    private boolean isOnOrAfter(String key, LocalDate since) {
        String[] parts = key.split("/");
        if (parts.length < 4) return true; // keep if we can't parse
        try {
            LocalDate keyDate = LocalDate.of(
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3])
            );
            return !keyDate.isBefore(since);
        } catch (NumberFormatException | DateTimeParseException e) {
            return true;
        }
    }

    private void processFile(String key, Map<String, Map<LocalDate, Integer>> totals, LambdaLogger logger) {
        try (var response = s3.getObject(r -> r.bucket(eventsBucket).key(key));
             var reader = new BufferedReader(new InputStreamReader(response, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                parsePosEvent(line, totals);
            }
        } catch (Exception e) {
            logger.log("WARN TrainingDataPreparer: failed to read " + key + ": " + e.getMessage());
        }
    }

    private void parsePosEvent(String json, Map<String, Map<LocalDate, Integer>> totals) {
        try {
            JsonNode node = mapper.readTree(json);
            String skuId   = textOrNull(node, "skuId");
            String storeId = textOrNull(node, "storeId");
            int    qty     = node.path("quantity").asInt(0);
            String ts      = textOrNull(node, "soldAt");
            if (ts == null) ts = textOrNull(node, "timestamp");

            if (skuId == null || storeId == null || ts == null || qty <= 0) return;
            String dcId = STORE_TO_DC.get(storeId);
            if (dcId == null) return;

            LocalDate saleDate = LocalDate.parse(ts.substring(0, 10));
            String seriesKey   = skuId + "|" + dcId;
            totals.computeIfAbsent(seriesKey, k -> new HashMap<>())
                  .merge(saleDate, qty, Integer::sum);
        } catch (Exception ignored) {
            // malformed record — skip
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode f = node.path(field);
        return f.isMissingNode() || f.isNull() ? null : f.asText(null);
    }

    // ── JSON Lines builder ────────────────────────────────────────────────────

    private byte[] buildJsonLines(
            Map<String, Map<LocalDate, Integer>> dailySales,
            LocalDate start,
            LocalDate end
    ) {
        StringBuilder sb = new StringBuilder();
        for (String skuId : SKU_ORDER) {
            int skuIdx = SKU_ORDER.indexOf(skuId);
            for (String dcId : DC_ORDER) {
                int dcIdx  = DC_ORDER.indexOf(dcId);
                Map<LocalDate, Integer> daily = dailySales.getOrDefault(skuId + "|" + dcId, Map.of());
                List<Integer> target = buildTarget(daily, start, end);
                sb.append(toJsonLine(skuId, dcId, skuIdx, dcIdx, start, target)).append('\n');
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildPredictJsonLines(
            Map<String, Map<LocalDate, Integer>> dailySales,
            LocalDate dataEnd
    ) {
        LocalDate ctxStart = dataEnd.minusDays(CONTEXT_LENGTH);
        return buildJsonLines(dailySales, ctxStart, dataEnd);
    }

    private static List<Integer> buildTarget(
            Map<LocalDate, Integer> daily,
            LocalDate start,
            LocalDate end
    ) {
        List<Integer> result = new ArrayList<>();
        LocalDate d = start;
        while (d.isBefore(end)) {
            result.add(daily.getOrDefault(d, 0));
            d = d.plusDays(1);
        }
        return result;
    }

    private String toJsonLine(
            String skuId, String dcId, int skuIdx, int dcIdx,
            LocalDate start, List<Integer> target
    ) {
        ObjectNode node = mapper.createObjectNode();
        node.put("start",   start.toString());
        node.put("item_id", skuId + "_" + dcId);
        ArrayNode cat = mapper.createArrayNode();
        cat.add(skuIdx);
        cat.add(dcIdx);
        node.set("cat", cat);
        ArrayNode tgt = mapper.createArrayNode();
        target.forEach(tgt::add);
        node.set("target", tgt);
        return node.toString();
    }

    // ── S3 put ────────────────────────────────────────────────────────────────

    private void put(String key, byte[] data, LambdaLogger logger) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(sagemakerBucket)
                        .key(key)
                        .contentType("application/jsonlines")
                        .contentLength((long) data.length)
                        .build(),
                RequestBody.fromBytes(data)
        );
        logger.log("TrainingDataPreparer: uploaded s3://" + sagemakerBucket + "/" + key
                   + " (" + data.length + " bytes)");
    }
}
