package com.smartretail.lambda.kinesis;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

public class KinesisConsumerHandler implements RequestHandler<KinesisEvent, Void> {

    private static final long IDEMPOTENCY_TTL_SECONDS = 48 * 60 * 60;

    private final ObjectMapper objectMapper;
    private final SisApiClient sisApiClient;
    private final DynamoDbClient dynamoDb;
    private final String idempotencyTable;

    public KinesisConsumerHandler() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.sisApiClient = new SisApiClient(requireEnv("SIS_ENDPOINT"));
        this.dynamoDb = DynamoDbClient.create();
        this.idempotencyTable = requireEnv("IDEMPOTENCY_TABLE_NAME");
    }

    @Override
    public Void handleRequest(KinesisEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        int processed = 0, skipped = 0, failed = 0;

        for (KinesisEvent.KinesisEventRecord record : event.getRecords()) {
            String data = StandardCharsets.UTF_8.decode(
                    record.getKinesis().getData()).toString();
            try {
                PosEventPayload payload = objectMapper.readValue(data, PosEventPayload.class);
                String eventId = sha256Hex(payload.transactionId().toString());

                if (isDuplicate(eventId)) {
                    logger.log("DUPLICATE skipped: transactionId=" + payload.transactionId());
                    skipped++;
                    continue;
                }

                int status = sisApiClient.postEvent(payload, logger);

                if (status == 202) {
                    markProcessed(eventId);
                    logger.log("ACCEPTED: transactionId=" + payload.transactionId());
                    processed++;
                } else {
                    // 409 — SIS detected duplicate (race condition)
                    logger.log("DUPLICATE (SIS): transactionId=" + payload.transactionId());
                    skipped++;
                }
            } catch (Exception e) {
                logger.log("FAILED record sequenceNumber=" + record.getKinesis().getSequenceNumber()
                        + " error=" + e.getMessage());
                failed++;
                // Re-throw to trigger bisect-on-error retry behaviour for the failed shard
                throw new RuntimeException("Failed to process Kinesis record", e);
            }
        }

        logger.log("Batch complete: processed=" + processed + " skipped=" + skipped + " failed=" + failed);
        return null;
    }

    private boolean isDuplicate(String eventId) {
        var response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(idempotencyTable)
                .key(Map.of("event_id", AttributeValue.fromS(eventId)))
                .build());
        return response.hasItem();
    }

    private void markProcessed(String eventId) {
        long expiresAt = Instant.now().getEpochSecond() + IDEMPOTENCY_TTL_SECONDS;
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(idempotencyTable)
                .item(Map.of(
                        "event_id", AttributeValue.fromS(eventId),
                        "expires_at", AttributeValue.fromN(String.valueOf(expiresAt))
                ))
                .build());
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
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
